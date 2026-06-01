package kz.courier.logisticsservice.service;

import kz.courier.logisticsservice.dto.LogisticsDto;
import kz.courier.logisticsservice.dto.NearbycourierProjection;
import kz.courier.logisticsservice.entity.AssignmentHistory;
import kz.courier.logisticsservice.entity.AssignmentStatus;
import kz.courier.logisticsservice.entity.CourierAssignment;
import kz.courier.logisticsservice.exception.AssignmentNotFoundException;
import kz.courier.logisticsservice.exception.BusinessException;
import kz.courier.logisticsservice.exception.LocationNotFoundException;
import kz.courier.logisticsservice.grpc.OrderGrpcClient;
import kz.courier.logisticsservice.kafka.AssignmentEventPublisher;
import kz.courier.logisticsservice.mapper.AssignmentMapper;
import kz.courier.logisticsservice.repository.AssignmentHistoryRepository;
import kz.courier.logisticsservice.repository.AssignmentRepository;
import kz.courier.logisticsservice.repository.CourierLocationRepository;
import kz.courier.logisticsservice.security.GatewayPrincipalProvider;
import kz.courier.order.v1.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogisticsService {

    private static final List<AssignmentStatus> TERMINAL_ASSIGNMENT_STATUSES =
            List.of(AssignmentStatus.DELIVERED, AssignmentStatus.CANCELLED, AssignmentStatus.FAILED);
    private static final Set<OrderStatus> AUTO_ASSIGNABLE_ORDER_STATUSES =
            EnumSet.of(OrderStatus.NEW, OrderStatus.ACCEPTED, OrderStatus.PREPARING,
                    OrderStatus.READY, OrderStatus.ASSIGNED);
    private static final double AUTO_ASSIGN_RADIUS_METERS = 5_000.0;
    private static final int AUTO_ASSIGN_LIMIT = 20;
    private static final long MAX_LOCATION_AGE_MINUTES = 120;
    private static final double AVERAGE_COURIER_SPEED_METERS_PER_MINUTE = 250.0;
    private static final double DISTANCE_WEIGHT = 0.80;
    private static final double FRESHNESS_WEIGHT = 0.20;
    private static final Set<String> PRIVILEGED_LOCATION_ROLES =
            Set.of("ADMIN", "SUPER_ADMIN");
    private static final Set<String> PRIVILEGED_ASSIGNMENT_ROLES =
            Set.of("ADMIN", "SUPER_ADMIN");

    private final AssignmentRepository assignmentRepository;
    private final AssignmentHistoryRepository historyRepository;
    private final CourierLocationRepository locationRepository;
    private final AssignmentMapper mapper;
    private final AssignmentEventPublisher eventPublisher;
    private final OrderGrpcClient orderGrpcClient;
    private final GatewayPrincipalProvider gatewayPrincipalProvider;

    // =========================================================================
    //  Assignment - Create
    // =========================================================================

    @Transactional
    public LogisticsDto.AssignmentResponse createAssignment(LogisticsDto.CreateAssignmentRequest req) {
        log.info("Creating assignment order={} courier={}", req.orderId(), req.courierId());

        return mapper.toResponse(createAssignmentInternal(
                req.orderId(),
                req.courierId(),
                req.assignedBy(),
                req.etaMinutes(),
                "manual-create"));
    }

    // =========================================================================
    //  Assignment - Auto-assign
    // =========================================================================

    /**
     * Automatically assigns the best courier for the given order.
     *
     * <p>Workflow:
     * <ol>
     *   <li>Load pickup coordinates from {@code order-service} over gRPC.</li>
     *   <li>Query PostGIS using {@link #findNearbyCouriers(double, double, double, int)}.</li>
     *   <li>Discard couriers that are already busy or whose GPS position is stale.</li>
     *   <li>Score remaining couriers and select the best candidate.</li>
     *   <li>Persist assignment, history, and Kafka event in one transaction.</li>
     * </ol>
     *
     * <p>Diploma note: the score intentionally gives 80% weight to distance and
     * 20% to location freshness. In courier dispatch, travel time to pickup is
     * the main SLA driver, while freshness guards against selecting a courier
     * whose coordinates are no longer reliable.
     */
    @Transactional
    public LogisticsDto.AutoAssignResponse autoAssign(UUID orderId) {
        UUID actorId = gatewayPrincipalProvider.requireCurrentUserId();
        log.info("Starting auto-assignment orderId={} actorId={}", orderId, actorId);

        ensureNoActiveAssignment(orderId);

        OrderGrpcClient.OrderSnapshot order = orderGrpcClient.getOrder(orderId);
        validateOrderIsAutoAssignable(order);

        List<LogisticsDto.NearbyCourierResponse> nearbyCouriers = findNearbyCouriers(
                order.pickupLatitude(),
                order.pickupLongitude(),
                AUTO_ASSIGN_RADIUS_METERS,
                AUTO_ASSIGN_LIMIT);

        log.info("Auto-assignment found {} nearby couriers for order={} pickup={}",
                nearbyCouriers.size(), orderId, order.pickupAddressLabel());

        List<CourierCandidateScore> eligibleCandidates = rankEligibleCouriers(nearbyCouriers);
        if (eligibleCandidates.isEmpty()) {
            throw new BusinessException("NO_COURIER_AVAILABLE",
                    "No eligible courier found within " + AUTO_ASSIGN_RADIUS_METERS
                            + " meters for order " + orderId);
        }

        CourierCandidateScore selected = eligibleCandidates.stream()
                .sorted(Comparator.comparingDouble(CourierCandidateScore::totalScore).reversed()
                        .thenComparingDouble(CourierCandidateScore::distanceMeters))
                .findFirst()
                .orElseThrow(() -> new BusinessException("NO_COURIER_AVAILABLE",
                        "Unable to select courier for order " + orderId));

        CourierAssignment assignment = createAssignmentInternal(
                orderId,
                selected.courierId(),
                actorId,
                selected.etaMinutes(),
                "auto-assign");

        log.info("Auto-assignment completed orderId={} assignmentId={} courierId={} totalScore={} distanceMeters={} etaMinutes={}",
                orderId, assignment.getId(), selected.courierId(), selected.totalScore(),
                selected.distanceMeters(), selected.etaMinutes());

        return LogisticsDto.AutoAssignResponse.builder()
                .assigned(mapper.toResponse(assignment))
                .selectedCourier(LogisticsDto.AutoAssignedCourier.builder()
                        .courierId(selected.courierId())
                        .distanceMeters(selected.distanceMeters())
                        .etaMinutes(selected.etaMinutes())
                        .distanceScore(selected.distanceScore())
                        .freshnessScore(selected.freshnessScore())
                        .totalScore(selected.totalScore())
                        .locationUpdatedAt(selected.locationUpdatedAt())
                        .build())
                .scannedCouriers(nearbyCouriers.size())
                .eligibleCouriers(eligibleCandidates.size())
                .searchRadiusMeters(AUTO_ASSIGN_RADIUS_METERS)
                .evaluatedAt(OffsetDateTime.now())
                .build();
    }

    // =========================================================================
    //  Assignment - Get / List
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
    //  Assignment - Status transition
    // =========================================================================

    @Transactional
    public LogisticsDto.AssignmentResponse updateStatus(UUID id, LogisticsDto.UpdateStatusRequest req) {
        CourierAssignment assignment = findAssignment(id);
        requireAssignmentActor(assignment, "change assignment status");
        AssignmentStatus oldStatus = assignment.getAssignmentStatus();

        if (oldStatus.isTerminal()) {
            throw new BusinessException("TERMINAL_STATUS",
                    "Assignment " + id + " is already in terminal status: " + oldStatus);
        }

        validateTransition(oldStatus, req.newStatus());

        if (req.newStatus() == AssignmentStatus.DELIVERED) {
            throw new BusinessException("DEDICATED_FLOW_REQUIRED",
                    "Use /assignments/{id}/verify-delivery-code to complete delivery");
        }

        assignment.setAssignmentStatus(req.newStatus());
        applyTimestamps(assignment, req.newStatus());
        assignment = assignmentRepository.save(assignment);

        UUID changedBy = gatewayPrincipalProvider.requireCurrentUserId();
        recordHistory(id, oldStatus, req.newStatus(), changedBy, req.reason());
        log.info("Assignment {} transitioned {} -> {}", id, oldStatus, req.newStatus());

        eventPublisher.publishStatusChanged(
                id, assignment.getOrderId(), assignment.getCourierId(),
                oldStatus, req.newStatus());

        return mapper.toResponse(assignment);
    }

    /**
     * Verifies customer OTP and closes the assignment as DELIVERED.
     *
     * <p>The courier calls this logistics endpoint, not order-service directly.
     * This keeps the assigned-courier rule close to assignment data. The OTP is
     * verified synchronously by order-service; only after success do we mark the
     * assignment delivered and publish the final logistics event.
     */
    @Transactional
    public LogisticsDto.AssignmentResponse verifyDeliveryCode(
            UUID id,
            LogisticsDto.VerifyDeliveryCodeRequest req) {

        CourierAssignment assignment = findAssignment(id);
        requireAssignmentActor(assignment, "verify delivery code");

        if (assignment.getAssignmentStatus() != AssignmentStatus.ARRIVED) {
            throw new BusinessException("INVALID_STATUS",
                    "Delivery code can be verified only after courier arrival");
        }

        OrderStatus orderStatus = orderGrpcClient.verifyDeliveryCode(
                assignment.getOrderId(), req.confirmationCode());
        if (orderStatus != OrderStatus.DELIVERED) {
            throw new BusinessException("ORDER_NOT_DELIVERED",
                    "order-service did not mark the order as delivered");
        }

        AssignmentStatus oldStatus = assignment.getAssignmentStatus();
        assignment.setAssignmentStatus(AssignmentStatus.DELIVERED);
        applyTimestamps(assignment, AssignmentStatus.DELIVERED);
        assignment = assignmentRepository.save(assignment);

        UUID changedBy = gatewayPrincipalProvider.requireCurrentUserId();
        recordHistory(id, oldStatus, AssignmentStatus.DELIVERED, changedBy, "otp-verified");
        eventPublisher.publishStatusChanged(
                id, assignment.getOrderId(), assignment.getCourierId(),
                oldStatus, AssignmentStatus.DELIVERED);

        log.info("Assignment {} delivered after OTP verification orderId={} courierId={}",
                id, assignment.getOrderId(), assignment.getCourierId());
        return mapper.toResponse(assignment);
    }

    /**
     * Resends the current OTP, or asks order-service to regenerate it when the
     * old code expired. Only the assigned courier or admin may trigger resend.
     */
    @Transactional(readOnly = true)
    public LogisticsDto.ResendDeliveryCodeResponse resendDeliveryCode(UUID id) {
        CourierAssignment assignment = findAssignment(id);
        requireAssignmentActor(assignment, "resend delivery code");

        if (assignment.getAssignmentStatus() != AssignmentStatus.ARRIVED) {
            throw new BusinessException("INVALID_STATUS",
                    "Delivery code can be resent only after courier arrival");
        }

        OrderGrpcClient.ResendDeliveryCodeResult result =
                orderGrpcClient.resendDeliveryConfirmationCode(assignment.getOrderId());

        if (result.status() != OrderStatus.DELIVERY_CONFIRMATION_PENDING) {
            throw new BusinessException("ORDER_NOT_PENDING_CONFIRMATION",
                    "order-service is not waiting for delivery confirmation");
        }

        log.info("Delivery code resent assignmentId={} orderId={} regenerated={}",
                id, assignment.getOrderId(), result.regenerated());

        return LogisticsDto.ResendDeliveryCodeResponse.builder()
                .assignmentId(id)
                .orderId(assignment.getOrderId())
                .assignmentStatus(assignment.getAssignmentStatus())
                .regenerated(result.regenerated())
                .expiresAt(result.expiresAt())
                .build();
    }

    // =========================================================================
    //  Assignment - History
    // =========================================================================

    @Transactional(readOnly = true)
    public List<LogisticsDto.HistoryEntry> getHistory(UUID assignmentId) {
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
    //  Courier Location - Upsert
    // =========================================================================

    @Transactional
    public LogisticsDto.CourierLocationResponse updateMyLocation(
            LogisticsDto.UpdateLocationRequest req) {

        UUID courierId = requireCurrentCourierId();
        return updateLocation(courierId, req);
    }

    /**
     * Updates courier location after enforcing self-or-admin access.
     *
     * <p>The authorization check lives in the service layer so the same rule
     * remains in force even if this method is called outside the REST controller.
     */
    @Transactional
    public LogisticsDto.CourierLocationResponse updateLocation(
            UUID courierId, LogisticsDto.UpdateLocationRequest req) {

        requireCurrentCourierId();

        locationRepository.upsertLocation(
                courierId, req.latitude(), req.longitude(),
                OffsetDateTime.now(), req.isOnline());

        log.debug("Location upserted courier={} lat={} lng={} online={}",
                courierId, req.latitude(), req.longitude(), req.isOnline());

        eventPublisher.publishLocationUpdated(courierId, req.latitude(), req.longitude(), req.isOnline());

        return mapper.toLocationResponse(
                locationRepository.findById(courierId)
                        .orElseThrow(() -> new LocationNotFoundException(courierId)));
    }

    @Transactional(readOnly = true)
    public LogisticsDto.CourierLocationResponse getMyLocation() {
        UUID courierId = requireCurrentCourierId();
        return getLocation(courierId);
    }

    /**
     * Reads courier location after enforcing self-or-admin access.
     */
    @Transactional(readOnly = true)
    public LogisticsDto.CourierLocationResponse getLocation(UUID courierId) {
        requireSelfOrAdmin(courierId, "read courier location");

        return mapper.toLocationResponse(
                locationRepository.findById(courierId)
                        .orElseThrow(() -> new LocationNotFoundException(courierId)));
    }

    // =========================================================================
    //  Courier Location - Online status
    // =========================================================================

    @Transactional
    public LogisticsDto.CourierLocationResponse updateMyOnlineStatus(
            LogisticsDto.UpdateOnlineStatusRequest req) {

        UUID courierId = requireCurrentCourierId();
        return updateOnlineStatus(courierId, req);
    }

    /**
     * Updates courier online status after enforcing self-or-admin access.
     */
    @Transactional
    public LogisticsDto.CourierLocationResponse updateOnlineStatus(
            UUID courierId, LogisticsDto.UpdateOnlineStatusRequest req) {

        requireSelfOrAdmin(courierId, "change courier online status");

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

    private UUID requireCurrentCourierId() {
        if (!gatewayPrincipalProvider.hasRole("COURIER")) {
            throw new BusinessException("FORBIDDEN",
                    "Only couriers may use courier self-service endpoints");
        }
        return gatewayPrincipalProvider.requireCurrentUserId();
    }

    /**
     * Defense-in-depth authorization guard for courier-owned resources.
     *
     * <p>Only the courier themself or an explicitly privileged admin may access
     * or modify the target courier resource.
     */
    private void requireSelfOrAdmin(UUID targetCourierId, String action) {
        UUID currentUserId = gatewayPrincipalProvider.requireCurrentUserId();

        if (currentUserId.equals(targetCourierId)) {
            return;
        }

        if ("read courier location".equals(action)) {
            if (gatewayPrincipalProvider.hasAnyRole("CLIENT", "USER", "ADMIN", "SUPER_ADMIN")) {
                return;
            }
        }

        if (gatewayPrincipalProvider.hasAnyRole(PRIVILEGED_LOCATION_ROLES.toArray(String[]::new))) {
            log.warn("Admin override action={} actorId={} targetCourierId={}",
                    action, currentUserId, targetCourierId);
            return;
        }

        throw new BusinessException("FORBIDDEN",
                "You may only " + action + " for your own courier profile");
    }

    /**
     * Only the assigned courier may move their delivery assignment forward.
     * Managers/admins are allowed as explicit operational override.
     */
    private void requireAssignmentActor(CourierAssignment assignment, String action) {
        UUID currentUserId = gatewayPrincipalProvider.requireCurrentUserId();

        if (currentUserId.equals(assignment.getCourierId())) {
            return;
        }

        if (gatewayPrincipalProvider.hasAnyRole(PRIVILEGED_ASSIGNMENT_ROLES.toArray(String[]::new))) {
            log.warn("Assignment admin override action={} actorId={} assignmentId={} courierId={}",
                    action, currentUserId, assignment.getId(), assignment.getCourierId());
            return;
        }

        throw new BusinessException("FORBIDDEN",
                "Only the assigned courier can " + action);
    }

    private CourierAssignment createAssignmentInternal(
            UUID orderId,
            UUID courierId,
            UUID assignedBy,
            Integer etaMinutes,
            String source) {

        ensureNoActiveAssignment(orderId);

        CourierAssignment assignment = CourierAssignment.builder()
                .orderId(orderId)
                .courierId(courierId)
                .assignedBy(assignedBy)
                .assignmentStatus(AssignmentStatus.ASSIGNED)
                .assignedAt(OffsetDateTime.now())
                .etaMinutes(etaMinutes)
                .build();

        assignment = assignmentRepository.save(assignment);
        log.info("Assignment persisted id={} orderId={} courierId={} source={} etaMinutes={}",
                assignment.getId(), orderId, courierId, source, etaMinutes);

        recordHistory(assignment.getId(), null, AssignmentStatus.ASSIGNED, assignedBy, source);
        eventPublisher.publishAssignmentCreated(
                assignment.getId(), assignment.getOrderId(), assignment.getCourierId());

        return assignment;
    }

    private void ensureNoActiveAssignment(UUID orderId) {
        boolean alreadyActive = assignmentRepository.existsByOrderIdAndAssignmentStatusNotIn(
                orderId, TERMINAL_ASSIGNMENT_STATUSES);
        if (alreadyActive) {
            throw new BusinessException("DUPLICATE_ASSIGNMENT",
                    "An active assignment already exists for order " + orderId);
        }
    }

    private void validateOrderIsAutoAssignable(OrderGrpcClient.OrderSnapshot order) {
        if (!AUTO_ASSIGNABLE_ORDER_STATUSES.contains(order.status())) {
            throw new BusinessException("ORDER_NOT_ASSIGNABLE",
                    "Order " + order.orderId() + " with status " + order.status()
                            + " cannot be auto-assigned");
        }
    }

    private List<CourierCandidateScore> rankEligibleCouriers(
            List<LogisticsDto.NearbyCourierResponse> nearbyCouriers) {

        if (nearbyCouriers.isEmpty()) {
            return List.of();
        }

        List<UUID> courierIds = nearbyCouriers.stream()
                .map(LogisticsDto.NearbyCourierResponse::courierId)
                .toList();
        Set<UUID> busyCouriers = Set.copyOf(assignmentRepository.findBusyCourierIds(courierIds));
        OffsetDateTime now = OffsetDateTime.now();

        return nearbyCouriers.stream()
                .filter(candidate -> filterNotBusy(candidate, busyCouriers))
                .filter(candidate -> filterFreshEnough(candidate, now))
                .map(candidate -> scoreCourier(candidate, now))
                .toList();
    }

    private boolean filterNotBusy(
            LogisticsDto.NearbyCourierResponse candidate,
            Set<UUID> busyCouriers) {

        boolean available = !busyCouriers.contains(candidate.courierId());
        if (!available) {
            log.debug("Skipping courier={} because courier already has active assignment",
                    candidate.courierId());
        }
        return available;
    }

    private boolean filterFreshEnough(
            LogisticsDto.NearbyCourierResponse candidate,
            OffsetDateTime now) {

        long locationAgeMinutes = Math.max(0, ChronoUnit.MINUTES.between(candidate.updatedAt(), now));
        boolean freshEnough = locationAgeMinutes <= MAX_LOCATION_AGE_MINUTES;
        if (!freshEnough) {
            log.debug("Skipping courier={} because location is stale age={}min limit={}min",
                    candidate.courierId(), locationAgeMinutes, MAX_LOCATION_AGE_MINUTES);
        }
        return freshEnough;
    }

    private CourierCandidateScore scoreCourier(
            LogisticsDto.NearbyCourierResponse candidate,
            OffsetDateTime now) {

        double distanceMeters = candidate.distanceMeters() != null
                ? candidate.distanceMeters()
                : AUTO_ASSIGN_RADIUS_METERS;
        long locationAgeMinutes = Math.max(0, ChronoUnit.MINUTES.between(candidate.updatedAt(), now));

        double distanceScore = normalizeScore(1.0 - (distanceMeters / AUTO_ASSIGN_RADIUS_METERS));
        double freshnessScore = normalizeScore(1.0 - ((double) locationAgeMinutes / MAX_LOCATION_AGE_MINUTES));

        // Distance dominates because it best approximates pickup ETA.
        // Freshness reduces the chance of dispatching a courier with stale GPS.
        double totalScore = roundScore(distanceScore * DISTANCE_WEIGHT
                + freshnessScore * FRESHNESS_WEIGHT);
        int etaMinutes = estimateEtaMinutes(distanceMeters);

        log.debug("Courier candidate courierId={} distance={}m age={}min distanceScore={} freshnessScore={} totalScore={}",
                candidate.courierId(), distanceMeters, locationAgeMinutes,
                distanceScore, freshnessScore, totalScore);

        return new CourierCandidateScore(
                candidate.courierId(),
                roundScore(distanceMeters),
                distanceScore,
                freshnessScore,
                totalScore,
                etaMinutes,
                candidate.updatedAt());
    }

    private int estimateEtaMinutes(double distanceMeters) {
        return Math.max(1, (int) Math.ceil(distanceMeters / AVERAGE_COURIER_SPEED_METERS_PER_MINUTE));
    }

    private double normalizeScore(double rawScore) {
        return roundScore(Math.max(0.0, Math.min(1.0, rawScore)));
    }

    private double roundScore(double value) {
        return Math.round(value * 100.0) / 100.0;
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
     * Only sets a timestamp once and ignores repeated transitions to the same bucket.
     */
    private void applyTimestamps(CourierAssignment assignment, AssignmentStatus newStatus) {
        OffsetDateTime now = OffsetDateTime.now();
        switch (newStatus) {
            case ACCEPTED -> {
                if (assignment.getAcceptedAt() == null) {
                    assignment.setAcceptedAt(now);
                }
            }
            case PICKED_UP -> {
                if (assignment.getPickedUpAt() == null) {
                    assignment.setPickedUpAt(now);
                }
            }
            case DELIVERED -> {
                if (assignment.getDeliveredAt() == null) {
                    assignment.setDeliveredAt(now);
                }
            }
            case CANCELLED -> {
                if (assignment.getCancelledAt() == null) {
                    assignment.setCancelledAt(now);
                }
            }
            default -> {
                // No dedicated timestamp column for this transition.
            }
        }
    }

    /**
     * Minimal allowed-transition guard.
     * Extend with a dedicated FSM validator when the process becomes richer.
     */
    private void validateTransition(AssignmentStatus from, AssignmentStatus to) {
        boolean valid = switch (from) {
            case PENDING -> to == AssignmentStatus.ASSIGNED || to == AssignmentStatus.CANCELLED;
            case ASSIGNED -> to == AssignmentStatus.ACCEPTED || to == AssignmentStatus.REJECTED
                    || to == AssignmentStatus.CANCELLED;
            case ACCEPTED -> to == AssignmentStatus.PICKED_UP || to == AssignmentStatus.CANCELLED;
            case PICKED_UP -> to == AssignmentStatus.IN_TRANSIT;
            case IN_TRANSIT -> to == AssignmentStatus.ARRIVED || to == AssignmentStatus.FAILED;
            case ARRIVED -> to == AssignmentStatus.DELIVERED || to == AssignmentStatus.FAILED;
            default -> false;
        };

        if (!valid) {
            throw new BusinessException("INVALID_TRANSITION",
                    "Transition " + from + " -> " + to + " is not allowed");
        }
    }

    private record CourierCandidateScore(
            UUID courierId,
            double distanceMeters,
            double distanceScore,
            double freshnessScore,
            double totalScore,
            int etaMinutes,
            OffsetDateTime locationUpdatedAt
    ) {}
}
