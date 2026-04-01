package kz.courier.logisticsservice.service;

import kz.courier.logisticsservice.dto.LogisticsDto;
import kz.courier.logisticsservice.dto.NearbycourierProjection;
import kz.courier.logisticsservice.entity.AssignmentHistory;
import kz.courier.logisticsservice.entity.AssignmentStatus;
import kz.courier.logisticsservice.entity.CourierAssignment;
import kz.courier.logisticsservice.entity.CourierLocation;
import kz.courier.logisticsservice.exception.AssignmentNotFoundException;
import kz.courier.logisticsservice.exception.BusinessException;
import kz.courier.logisticsservice.exception.LocationNotFoundException;
import kz.courier.logisticsservice.mapper.AssignmentMapper;
import kz.courier.logisticsservice.kafka.AssignmentEventPublisher;
import kz.courier.logisticsservice.repository.AssignmentHistoryRepository;
import kz.courier.logisticsservice.repository.AssignmentRepository;
import kz.courier.logisticsservice.repository.CourierLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogisticsService {
    private final AssignmentRepository        assignmentRepository;
    private final AssignmentHistoryRepository historyRepository;
    private final CourierLocationRepository   locationRepository;
    private final AssignmentMapper            mapper;
    private final AssignmentEventPublisher    eventPublisher;

    // =========================================================================
    //  Assignment — Create
    // =========================================================================

    @Transactional
    public LogisticsDto.AssignmentResponse createAssignment(LogisticsDto.CreateAssignmentRequest req) {
        log.info("Creating assignment order={} courier={}", req.orderId(), req.courierId());

        // Guard: prevent duplicate active assignment for the same order
        boolean alreadyActive = assignmentRepository.existsByOrderIdAndAssignmentStatusNotIn(
                req.orderId(),
                List.of(AssignmentStatus.DELIVERED, AssignmentStatus.CANCELLED, AssignmentStatus.FAILED));
        if (alreadyActive) {
            throw new BusinessException("DUPLICATE_ASSIGNMENT",
                    "An active assignment already exists for order " + req.orderId());
        }

        CourierAssignment assignment = CourierAssignment.builder()
                .orderId(req.orderId())
                .courierId(req.courierId())
                .assignedBy(req.assignedBy())
                .assignmentStatus(AssignmentStatus.ASSIGNED)
                .assignedAt(OffsetDateTime.now())
                .etaMinutes(req.etaMinutes())
                .build();

        assignment = assignmentRepository.save(assignment);
        log.info("Assignment created id={}", assignment.getId());

        recordHistory(assignment.getId(), null, AssignmentStatus.ASSIGNED, req.assignedBy(), null);

        // Notify downstream services (order-service, notification-service)
        eventPublisher.publishAssignmentCreated(
                assignment.getId(), assignment.getOrderId(), assignment.getCourierId());

        return mapper.toResponse(assignment);
    }

    // =========================================================================
    //  Assignment — Get / List
    // =========================================================================

    @Transactional(readOnly = true)
    public LogisticsDto.AssignmentResponse getAssignment(UUID id) {
        return mapper.toResponse(findAssignment(id));
    }

    @Transactional(readOnly = true)
    public LogisticsDto.PagedAssignments listAssignments(
            UUID courierId, UUID orderId, AssignmentStatus status,
            int page, int pageSize, String sortBy, boolean descending) {

        Sort sort = descending
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();

        Page<CourierAssignment> result = assignmentRepository.findAllFiltered(
                courierId, orderId, status,
                PageRequest.of(page - 1, pageSize, sort));

        return mapper.toPagedResponse(result);
    }

    // =========================================================================
    //  Assignment — Status transition
    // =========================================================================

    @Transactional
    public LogisticsDto.AssignmentResponse updateStatus(UUID id, LogisticsDto.UpdateStatusRequest req) {
        CourierAssignment assignment = findAssignment(id);
        AssignmentStatus oldStatus = assignment.getAssignmentStatus();

        if (oldStatus.isTerminal()) {
            throw new BusinessException("TERMINAL_STATUS",
                    "Assignment " + id + " is already in terminal status: " + oldStatus);
        }

        validateTransition(oldStatus, req.newStatus());

        assignment.setAssignmentStatus(req.newStatus());
        applyTimestamps(assignment, req.newStatus());
        assignment = assignmentRepository.save(assignment);

        recordHistory(id, oldStatus, req.newStatus(), req.changedBy(), req.reason());
        log.info("Assignment {} transitioned {} → {}", id, oldStatus, req.newStatus());

        // Notify downstream (order-service updates order status, notifications go out)
        eventPublisher.publishStatusChanged(
                id, assignment.getOrderId(), assignment.getCourierId(),
                oldStatus, req.newStatus());

        return mapper.toResponse(assignment);
    }

    // =========================================================================
    //  Assignment — History
    // =========================================================================

    @Transactional(readOnly = true)
    public List<LogisticsDto.HistoryEntry> getHistory(UUID assignmentId) {
        // Ensure the assignment exists before returning history
        if (!assignmentRepository.existsById(assignmentId)) {
            throw new AssignmentNotFoundException(assignmentId);
        }
        return historyRepository
                .findAllByAssignmentIdOrderByChangedAtAsc(assignmentId)
                .stream()
                .map(mapper::toHistoryEntry)
                .toList();
    }

    // =========================================================================
    //  Courier Location — Upsert
    // =========================================================================

    @Transactional
    public LogisticsDto.CourierLocationResponse updateLocation(
            UUID courierId, LogisticsDto.UpdateLocationRequest req) {

        locationRepository.upsertLocation(
                courierId, req.latitude(), req.longitude(),
                OffsetDateTime.now(), req.isOnline());

        log.debug("Location upserted courier={} lat={} lng={} online={}",
                courierId, req.latitude(), req.longitude(), req.isOnline());

        // Publish to Kafka so tracking dashboards and notification-service stay in sync
        eventPublisher.publishLocationUpdated(courierId, req.latitude(), req.longitude(), req.isOnline());

        // Return the freshly saved entity
        return mapper.toLocationResponse(
                locationRepository.findById(courierId)
                        .orElseThrow(() -> new LocationNotFoundException(courierId)));
    }

    @Transactional(readOnly = true)
    public LogisticsDto.CourierLocationResponse getLocation(UUID courierId) {
        return mapper.toLocationResponse(
                locationRepository.findById(courierId)
                        .orElseThrow(() -> new LocationNotFoundException(courierId)));
    }

    // =========================================================================
    //  Courier Location — Online status
    // =========================================================================

    @Transactional
    public LogisticsDto.CourierLocationResponse updateOnlineStatus(
            UUID courierId, LogisticsDto.UpdateOnlineStatusRequest req) {

        locationRepository.upsertOnlineStatus(courierId, req.isOnline());
        log.info("Courier {} is now {}", courierId, req.isOnline() ? "ONLINE" : "OFFLINE");

        return mapper.toLocationResponse(
                locationRepository.findById(courierId)
                        .orElseThrow(() -> new LocationNotFoundException(courierId)));
    }

    // =========================================================================
    //  Nearby Couriers
    // =========================================================================

    @Transactional(readOnly = true)
    public List<LogisticsDto.NearbyCourierResponse> findNearbyCouriers(
            double lat, double lng, double radiusMeters, int limit) {

        List<NearbycourierProjection> raw =
                locationRepository.findNearbyCouriers(lat, lng, radiusMeters, limit);
        return mapper.toNearbyCourierResponse(raw);
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    private CourierAssignment findAssignment(UUID id) {
        return assignmentRepository.findById(id)
                .orElseThrow(() -> new AssignmentNotFoundException(id));
    }

    private void recordHistory(UUID assignmentId,
                               AssignmentStatus oldStatus,
                               AssignmentStatus newStatus,
                               UUID changedBy,
                               String reason) {
        historyRepository.save(AssignmentHistory.builder()
                .assignmentId(assignmentId)
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .changedBy(changedBy)
                .reason(reason)
                .changedAt(OffsetDateTime.now())
                .build());
    }

    /**
     * Applies lifecycle timestamps when the status changes.
     * Only sets a timestamp once — ignores subsequent transitions to the same bucket.
     */
    private void applyTimestamps(CourierAssignment a, AssignmentStatus newStatus) {
        OffsetDateTime now = OffsetDateTime.now();
        switch (newStatus) {
            case ACCEPTED   -> { if (a.getAcceptedAt()  == null) a.setAcceptedAt(now);  }
            case PICKED_UP  -> { if (a.getPickedUpAt()  == null) a.setPickedUpAt(now);  }
            case DELIVERED  -> { if (a.getDeliveredAt() == null) a.setDeliveredAt(now); }
            case CANCELLED  -> { if (a.getCancelledAt() == null) a.setCancelledAt(now); }
            default -> { /* no dedicated timestamp column */ }
        }
    }

    /**
     * Minimal allowed-transition guard.
     * Extend with a full FSM table if the business rules become more complex.
     */
    private void validateTransition(AssignmentStatus from, AssignmentStatus to) {
        boolean valid = switch (from) {
            case PENDING    -> to == AssignmentStatus.ASSIGNED  || to == AssignmentStatus.CANCELLED;
            case ASSIGNED   -> to == AssignmentStatus.ACCEPTED  || to == AssignmentStatus.REJECTED
                    || to == AssignmentStatus.CANCELLED;
            case ACCEPTED   -> to == AssignmentStatus.PICKED_UP || to == AssignmentStatus.CANCELLED;
            case PICKED_UP  -> to == AssignmentStatus.IN_TRANSIT;
            case IN_TRANSIT -> to == AssignmentStatus.DELIVERED || to == AssignmentStatus.FAILED;
            default         -> false;
        };

        if (!valid) {
            throw new BusinessException("INVALID_TRANSITION",
                    "Transition " + from + " → " + to + " is not allowed");
        }
    }
}