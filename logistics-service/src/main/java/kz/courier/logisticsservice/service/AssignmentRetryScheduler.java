package kz.courier.logisticsservice.service;

import kz.courier.logisticsservice.entity.AssignmentFailureReason;
import kz.courier.logisticsservice.entity.CourierAssignment;
import kz.courier.logisticsservice.exception.BusinessException;
import kz.courier.logisticsservice.repository.AssignmentRepository;
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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AssignmentRetryScheduler {

    private final AssignmentRepository assignmentRepository;
    private final CapacityAwareAssignmentService assignmentService;
    private final SystemPrincipalRunner systemPrincipalRunner;
    private final PlatformTransactionManager transactionManager;

    @Value("${assignment.retry.enabled:true}")
    private boolean enabled;

    @Value("${assignment.retry.max-attempts:100}")
    private int maxAttempts;

    @Value("${assignment.retry.batch-size:10}")
    private int batchSize;

    @Value("${assignment.retry.delay-seconds:60}")
    private long retryDelaySeconds;

    @Scheduled(fixedDelayString = "${assignment.retry.fixed-delay-ms:60000}")
    public void retryDueManualRequiredAssignments() {
        if (!enabled) {
            return;
        }

        List<UUID> due = assignmentRepository.findDueManualRequiredRetryIds(
                temporaryReasons(), maxAttempts, batchSize);
        if (due.isEmpty()) {
            log.debug("[AssignmentRetryScheduler] No due manual-required assignments; maxAttempts={} batchSize={}",
                    maxAttempts, batchSize);
            return;
        }

        log.info("[AssignmentRetryScheduler] Retrying {} due manual-required assignments", due.size());
        for (UUID assignmentId : due) {
            runSingleRetryInNewTransaction(assignmentId);
        }
    }

    public void retryRetryableManualRequiredAssignmentsNow(String trigger) {
        if (!enabled) {
            return;
        }

        List<UUID> retryable = assignmentRepository.findUnresolvedManualRequiredIds(batchSize);
        if (retryable.isEmpty()) {
            retryTerminalOfferOrdersNow(trigger);
            return;
        }

        log.info("[AssignmentRetryScheduler] Admin-triggered retry for {} unresolved manual-required assignments trigger={} maxAttemptsIgnored=true",
                retryable.size(),
                trigger);
        for (UUID assignmentId : retryable) {
            runSingleRetryInNewTransaction(assignmentId);
        }
    }

    private List<String> temporaryReasons() {
        return Arrays.stream(AssignmentFailureReason.values())
                .filter(AssignmentFailureReason::isTemporary)
                .map(Enum::name)
                .toList();
    }

    private void retryOne(UUID assignmentId) {
        CourierAssignment failed = assignmentRepository.lockUnresolvedManualRequiredById(assignmentId)
                .orElse(null);
        if (failed == null) {
            log.debug("[AssignmentRetryScheduler] Retry skipped, manual-required assignment no longer unresolved assignmentId={}",
                    assignmentId);
            return;
        }

        int nextAttempt = (failed.getRetryCount() == null ? 0 : failed.getRetryCount()) + 1;
        log.info("[AssignmentRetryScheduler] Retry attempt {}/{} assignmentId={} orderId={} reason={} nextRetryAt={}",
                nextAttempt, maxAttempts, failed.getId(), failed.getOrderId(),
                failed.getFailureReason(), failed.getNextRetryAt());

        failed.setRetryCount(nextAttempt);
        failed.setLastRetryAt(OffsetDateTime.now());
        failed.setNextRetryAt(OffsetDateTime.now().plusSeconds(retryDelaySeconds));
        assignmentRepository.save(failed);

        try {
            var response = systemPrincipalRunner.run(() -> assignmentService.autoAssign(failed.getOrderId()));
            log.info("[AssignmentRetryScheduler] Retry result assignmentId={} orderId={} status={} failureReason={} message={}",
                    failed.getId(), failed.getOrderId(), response.assignmentStatus(),
                    response.failureReason(), response.message());
        } catch (BusinessException ex) {
            if ("DUPLICATE_ASSIGNMENT".equals(ex.getCode())) {
                failed.setResolvedAt(OffsetDateTime.now());
                assignmentRepository.save(failed);
                log.info("[AssignmentRetryScheduler] Retry skipped, order already assigned orderId={}",
                        failed.getOrderId());
                return;
            }
            log.warn("[AssignmentRetryScheduler] Retry failed orderId={} code={} message={}",
                    failed.getOrderId(), ex.getCode(), ex.getMessage());
        } catch (Exception e) {
            log.error("[AssignmentRetryScheduler] Retry crashed orderId={}", failed.getOrderId(), e);
        }
    }

    private void runSingleRetryInNewTransaction(UUID assignmentId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> retryOne(assignmentId));
    }

    private void retryTerminalOfferOrdersNow(String trigger) {
        List<UUID> orderIds = assignmentRepository.findOrdersNeedingReassignmentAfterTerminalOffer(batchSize);
        if (orderIds.isEmpty()) {
            log.debug("[AssignmentRetryScheduler] No unresolved manual-required or terminal-offer assignments for trigger={}; maxAttempts={} batchSize={}",
                    trigger, maxAttempts, batchSize);
            return;
        }

        log.info("[AssignmentRetryScheduler] Retrying {} terminal-offer orders immediately trigger={}",
                orderIds.size(), trigger);
        for (UUID orderId : orderIds) {
            runOrderRetryInNewTransaction(orderId, trigger);
        }
    }

    private void runOrderRetryInNewTransaction(UUID orderId, String trigger) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            try {
                var response = systemPrincipalRunner.run(() -> assignmentService.autoAssign(orderId));
                log.info("[AssignmentRetryScheduler] Terminal-offer retry result orderId={} trigger={} status={} courierId={} failureReason={} message={}",
                        orderId, trigger, response.assignmentStatus(), response.courierId(),
                        response.failureReason(), response.message());
            } catch (BusinessException ex) {
                if ("DUPLICATE_ASSIGNMENT".equals(ex.getCode())) {
                    log.info("[AssignmentRetryScheduler] Terminal-offer retry skipped, order already assigned orderId={} trigger={}",
                            orderId, trigger);
                    return;
                }
                log.warn("[AssignmentRetryScheduler] Terminal-offer retry failed orderId={} trigger={} code={} message={}",
                        orderId, trigger, ex.getCode(), ex.getMessage());
            } catch (Exception e) {
                log.error("[AssignmentRetryScheduler] Terminal-offer retry crashed orderId={} trigger={}",
                        orderId, trigger, e);
            }
        });
    }
}
