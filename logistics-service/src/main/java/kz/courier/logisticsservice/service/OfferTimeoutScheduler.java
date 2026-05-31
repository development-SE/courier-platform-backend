package kz.courier.logisticsservice.service;

import kz.courier.logisticsservice.entity.AssignmentHistory;
import kz.courier.logisticsservice.entity.AssignmentStatus;
import kz.courier.logisticsservice.entity.CourierAssignment;
import kz.courier.logisticsservice.entity.RouteStatus;
import kz.courier.logisticsservice.entity.RouteStopStatus;
import kz.courier.logisticsservice.grpc.OrderGrpcClient;
import kz.courier.logisticsservice.kafka.AssignmentEventPublisher;
import kz.courier.logisticsservice.repository.AssignmentHistoryRepository;
import kz.courier.logisticsservice.repository.AssignmentRepository;
import kz.courier.logisticsservice.repository.CourierRouteRepository;
import kz.courier.logisticsservice.repository.RouteStopRepository;
import kz.courier.logisticsservice.security.SystemPrincipalRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OfferTimeoutScheduler {

    private static final UUID SYSTEM_ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final AssignmentRepository assignmentRepository;
    private final AssignmentHistoryRepository historyRepository;
    private final CourierRouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final AssignmentEventPublisher eventPublisher;
    private final OrderGrpcClient orderGrpcClient;
    private final CapacityAwareAssignmentService assignmentService;
    private final SystemPrincipalRunner systemPrincipalRunner;
    private final PlatformTransactionManager transactionManager;

    @Value("${assignment.offer-timeout.enabled:true}")
    private boolean enabled;

    @Value("${assignment.offer-timeout.seconds:300}")
    private long offerTimeoutSeconds;

    @Value("${assignment.offer-timeout.batch-size:20}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${assignment.offer-timeout.fixed-delay-ms:15000}")
    public void timeoutPendingOffers() {
        if (!enabled) {
            return;
        }

        OffsetDateTime olderThan = OffsetDateTime.now().minusSeconds(offerTimeoutSeconds);
        List<UUID> timedOutOfferIds = assignmentRepository.findTimedOutPendingOfferIds(olderThan, batchSize);
        if (timedOutOfferIds.isEmpty()) {
            return;
        }

        int processed = 0;
        for (UUID assignmentId : timedOutOfferIds) {
            TimeoutResult result = runTimeoutInNewTransaction(assignmentId);
            if (result != null) {
                processed++;
                triggerReassignment(result);
            }
        }
        log.info("[OfferTimeoutScheduler] Timed out {} pending offers", processed);
    }

    private TimeoutResult runTimeoutInNewTransaction(UUID assignmentId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status -> processTimeout(assignmentId));
    }

    private TimeoutResult processTimeout(UUID assignmentId) {
        CourierAssignment assignment = assignmentRepository.lockPendingOfferById(assignmentId)
                .orElse(null);
        if (assignment == null) {
            log.debug("[OfferTimeoutScheduler] Timeout skipped, offer no longer pending assignmentId={}",
                    assignmentId);
            return null;
        }

        AssignmentStatus oldStatus = assignment.getAssignmentStatus();
        if (oldStatus != AssignmentStatus.PENDING) {
            return null;
        }

        assignment.setAssignmentStatus(AssignmentStatus.TIMED_OUT);
        assignment.setCancelledAt(OffsetDateTime.now());
        assignment.setCancellationReason("offer-timeout");
        assignmentRepository.save(assignment);
        releaseRouteImpact(assignment);

        historyRepository.save(AssignmentHistory.builder()
                .assignmentId(assignment.getId())
                .oldStatus(oldStatus)
                .newStatus(AssignmentStatus.TIMED_OUT)
                .changedBy(SYSTEM_ACTOR_ID)
                .reason("offer-timeout")
                .changedAt(OffsetDateTime.now())
                .build());

        eventPublisher.publishStatusChanged(
                assignment.getId(),
                assignment.getOrderId(),
                assignment.getCourierId(),
                oldStatus,
                AssignmentStatus.TIMED_OUT);

        return new TimeoutResult(assignment.getId(), assignment.getOrderId());
    }

    private void triggerReassignment(TimeoutResult result) {
        try {
            systemPrincipalRunner.run(() -> {
                orderGrpcClient.markAssignmentPending(result.orderId());
                return null;
            });
        } catch (Exception ex) {
            log.warn("[OfferTimeoutScheduler] Failed to set ASSIGNMENT_PENDING orderId={} reason={}",
                    result.orderId(), ex.getMessage());
        }

        try {
            var response = systemPrincipalRunner.run(() -> assignmentService.autoAssign(result.orderId()));
            log.info("[OfferTimeoutScheduler] Reassignment after offer timeout assignmentId={} orderId={} status={} courierId={} failureReason={}",
                    result.assignmentId(), result.orderId(), response.assignmentStatus(),
                    response.courierId(), response.failureReason());
        } catch (Exception ex) {
            log.warn("[OfferTimeoutScheduler] Reassignment failed orderId={} reason={}",
                    result.orderId(), ex.getMessage());
        }
    }

    private void releaseRouteImpact(CourierAssignment assignment) {
        if (assignment.getRouteId() == null || assignment.getDemandUnits() == null || assignment.getCourierId() == null) {
            return;
        }

        routeRepository.lockActiveRouteByCourierId(assignment.getCourierId())
                .filter(route -> route.getId().equals(assignment.getRouteId()))
                .ifPresent(route -> {
                    route.setCurrentLoadUnits(Math.max(0,
                            route.getCurrentLoadUnits() - assignment.getDemandUnits()));
                    route.setActiveOrdersCount(Math.max(0, route.getActiveOrdersCount() - 1));
                    routeRepository.save(route);
                });

        var stops = routeStopRepository.findAllByRouteIdOrderBySequenceNumberAsc(assignment.getRouteId());
        var changedStops = stops.stream()
                .filter(stop -> assignment.getOrderId().equals(stop.getOrderId()))
                .filter(stop -> stop.getStatus() != RouteStopStatus.COMPLETED
                        && stop.getStatus() != RouteStopStatus.CANCELLED)
                .peek(stop -> stop.setStatus(RouteStopStatus.CANCELLED))
                .toList();
        if (!changedStops.isEmpty()) {
            routeStopRepository.saveAll(changedStops);
        }

        routeRepository.findById(assignment.getRouteId()).ifPresent(route -> {
            var routeStops = routeStopRepository.findAllByRouteIdOrderBySequenceNumberAsc(route.getId());
            boolean hasActiveStops = routeStops.stream()
                    .anyMatch(stop -> stop.getStatus() != RouteStopStatus.CANCELLED
                            && stop.getStatus() != RouteStopStatus.COMPLETED);
            if (!hasActiveStops && route.getStatus() == RouteStatus.ACTIVE) {
                route.setStatus(RouteStatus.COMPLETED);
                routeRepository.save(route);
            }
        });
    }

    private record TimeoutResult(UUID assignmentId, UUID orderId) {
    }
}
