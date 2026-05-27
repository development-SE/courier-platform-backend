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
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AssignmentRetryScheduler {

    private final AssignmentRepository assignmentRepository;
    private final CapacityAwareAssignmentService assignmentService;
    private final SystemPrincipalRunner systemPrincipalRunner;

    @Value("${assignment.retry.enabled:true}")
    private boolean enabled;

    @Value("${assignment.retry.max-attempts:5}")
    private int maxAttempts;

    @Value("${assignment.retry.batch-size:10}")
    private int batchSize;

    @Value("${assignment.retry.delay-seconds:60}")
    private long retryDelaySeconds;

    @Scheduled(fixedDelayString = "${assignment.retry.fixed-delay-ms:60000}")
    @Transactional
    public void retryDueManualRequiredAssignments() {
        if (!enabled) {
            return;
        }

        List<String> temporaryReasons = Arrays.stream(AssignmentFailureReason.values())
                .filter(AssignmentFailureReason::isTemporary)
                .map(Enum::name)
                .toList();

        List<CourierAssignment> due = assignmentRepository.lockDueManualRequiredRetries(
                temporaryReasons, maxAttempts, batchSize);
        if (due.isEmpty()) {
            return;
        }

        log.info("[AssignmentRetryScheduler] Retrying {} manual-required assignments", due.size());
        for (CourierAssignment failed : due) {
            retryOne(failed);
        }
    }

    private void retryOne(CourierAssignment failed) {
        failed.setRetryCount((failed.getRetryCount() == null ? 0 : failed.getRetryCount()) + 1);
        failed.setLastRetryAt(OffsetDateTime.now());
        failed.setNextRetryAt(OffsetDateTime.now().plusSeconds(retryDelaySeconds));
        assignmentRepository.save(failed);

        try {
            systemPrincipalRunner.run(() -> assignmentService.autoAssign(failed.getOrderId()));
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
}
