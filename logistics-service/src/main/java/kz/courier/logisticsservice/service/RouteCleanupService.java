package kz.courier.logisticsservice.service;

import kz.courier.logisticsservice.entity.AssignmentStatus;
import kz.courier.logisticsservice.entity.CourierAssignment;
import kz.courier.logisticsservice.entity.CourierRoute;
import kz.courier.logisticsservice.entity.RouteStatus;
import kz.courier.logisticsservice.entity.RouteStop;
import kz.courier.logisticsservice.entity.RouteStopStatus;
import kz.courier.logisticsservice.exception.AssignmentNotFoundException;
import kz.courier.logisticsservice.grpc.OrderGrpcClient;
import kz.courier.logisticsservice.kafka.AssignmentEventPublisher;
import kz.courier.logisticsservice.repository.AssignmentRepository;
import kz.courier.logisticsservice.repository.CourierRouteRepository;
import kz.courier.logisticsservice.repository.RouteStopRepository;
import kz.courier.logisticsservice.security.GatewayPrincipalProvider;
import kz.courier.logisticsservice.security.SystemPrincipalRunner;
import kz.courier.order.v1.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RouteCleanupService {

    private final AssignmentRepository assignmentRepository;
    private final CourierRouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final OrderGrpcClient orderGrpcClient;
    private final AssignmentEventPublisher eventPublisher;
    private final SystemPrincipalRunner systemPrincipalRunner;
    private final GatewayPrincipalProvider gatewayPrincipalProvider;

    @Transactional
    public CleanupResult cleanupAssignment(UUID assignmentId, String reason, boolean reassignRequired) {
        CourierAssignment assignment = assignmentRepository.lockById(assignmentId)
                .orElseThrow(() -> new AssignmentNotFoundException(assignmentId));
        return cleanupAssignment(assignment, reason, reassignRequired);
    }

    @Transactional
    public CleanupResult cleanupAssignment(CourierAssignment assignment, String reason, boolean reassignRequired) {
        CourierAssignment locked = assignment.getId() == null
                ? assignment
                : assignmentRepository.lockById(assignment.getId())
                        .orElseThrow(() -> new AssignmentNotFoundException(assignment.getId()));

        boolean alreadyCleaned = locked.getRouteCleanedAt() != null;
        boolean capacityReleased = cleanupRouteImpact(locked, reason);
        int stopsCancelled = cancelUncompletedStops(locked);
        boolean routeCompleted = completeRouteIfEmpty(locked.getRouteId());
        boolean reassignmentRequested = isReassignmentEligible(locked.getOrderId(), reassignRequired);

        return new CleanupResult(
                locked.getId(),
                locked.getOrderId(),
                locked.getRouteId(),
                capacityReleased,
                stopsCancelled,
                routeCompleted,
                alreadyCleaned,
                reassignmentRequested);
    }

    @Transactional
    public List<CleanupResult> cleanupOrderCancellation(UUID orderId, String reason) {
        return assignmentRepository.lockUncleanedAssignmentsByOrderId(orderId).stream()
                .map(assignment -> cancelAssignmentForOrderCancellation(assignment, reason))
                .toList();
    }

    private CleanupResult cancelAssignmentForOrderCancellation(CourierAssignment assignment, String reason) {
        AssignmentStatus oldStatus = assignment.getAssignmentStatus();
        boolean statusChanged = oldStatus.isActive();
        if (statusChanged) {
            assignment.setAssignmentStatus(AssignmentStatus.CANCELLED);
            if (assignment.getCancelledAt() == null) {
                assignment.setCancelledAt(OffsetDateTime.now());
            }
            if (reason != null && !reason.isBlank()) {
                assignment.setCancellationReason(reason);
            }
            assignment = assignmentRepository.save(assignment);
        }

        CleanupResult result = cleanupAssignment(assignment, reason, false);
        if (statusChanged) {
            eventPublisher.publishStatusChanged(
                    assignment.getId(),
                    assignment.getOrderId(),
                    assignment.getCourierId(),
                    oldStatus,
                    AssignmentStatus.CANCELLED);
        }
        return result;
    }

    private boolean cleanupRouteImpact(CourierAssignment assignment, String reason) {
        if (assignment.getRouteId() == null
                || assignment.getDemandUnits() == null
                || assignment.getRouteCleanedAt() != null) {
            return false;
        }

        routeRepository.lockById(assignment.getRouteId()).ifPresent(route -> releaseCapacity(route, assignment));
        OffsetDateTime now = OffsetDateTime.now();
        assignment.setRouteCleanedAt(now);
        assignment.setRouteCleanupReason(truncateReason(reason));
        assignment.setRouteCleanupBy(resolveCleanupActor());
        assignmentRepository.save(assignment);
        return true;
    }

    private void releaseCapacity(CourierRoute route, CourierAssignment assignment) {
        int demandUnits = assignment.getDemandUnits() == null ? 0 : assignment.getDemandUnits();
        route.setCurrentLoadUnits(Math.max(0, route.getCurrentLoadUnits() - demandUnits));
        route.setActiveOrdersCount(Math.max(0, route.getActiveOrdersCount() - 1));
        routeRepository.save(route);
    }

    private int cancelUncompletedStops(CourierAssignment assignment) {
        if (assignment.getRouteId() == null) {
            return 0;
        }

        List<RouteStop> changed = routeStopRepository.findAllByRouteIdOrderBySequenceNumberAsc(assignment.getRouteId())
                .stream()
                .filter(stop -> assignment.getOrderId().equals(stop.getOrderId()))
                .filter(stop -> stop.getStatus() != RouteStopStatus.COMPLETED
                        && stop.getStatus() != RouteStopStatus.CANCELLED)
                .peek(stop -> stop.setStatus(RouteStopStatus.CANCELLED))
                .toList();
        if (!changed.isEmpty()) {
            routeStopRepository.saveAll(changed);
        }
        return changed.size();
    }

    private boolean completeRouteIfEmpty(UUID routeId) {
        if (routeId == null) {
            return false;
        }

        return routeRepository.lockById(routeId)
                .map(route -> {
                    boolean hasActiveStops = routeStopRepository.findAllByRouteIdOrderBySequenceNumberAsc(routeId)
                            .stream()
                            .anyMatch(stop -> stop.getStatus() != RouteStopStatus.COMPLETED
                                    && stop.getStatus() != RouteStopStatus.CANCELLED);
                    if (!hasActiveStops && route.getStatus() == RouteStatus.ACTIVE) {
                        route.setStatus(RouteStatus.COMPLETED);
                        routeRepository.save(route);
                        return true;
                    }
                    return false;
                })
                .orElse(false);
    }

    private boolean isReassignmentEligible(UUID orderId, boolean reassignRequired) {
        if (!reassignRequired) {
            return false;
        }

        try {
            OrderGrpcClient.OrderSnapshot order = systemPrincipalRunner.run(() -> orderGrpcClient.getOrder(orderId));
            if (order.status() == OrderStatus.CANCELLED || order.status() == OrderStatus.DELIVERED) {
                log.info("Skipping reassignment for terminal order orderId={} status={}", orderId, order.status());
                return false;
            }
            return true;
        } catch (Exception ex) {
            log.warn("Failed to check reassignment eligibility orderId={} reason={}", orderId, ex.getMessage());
            return false;
        }
    }

    private UUID resolveCleanupActor() {
        try {
            return gatewayPrincipalProvider.requireCurrentUserId();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String truncateReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return reason.length() <= 100 ? reason : reason.substring(0, 100);
    }

    public record CleanupResult(
            UUID assignmentId,
            UUID orderId,
            UUID routeId,
            boolean capacityReleased,
            int stopsCancelled,
            boolean routeCompleted,
            boolean alreadyCleaned,
            boolean reassignmentRequested
    ) {
    }
}
