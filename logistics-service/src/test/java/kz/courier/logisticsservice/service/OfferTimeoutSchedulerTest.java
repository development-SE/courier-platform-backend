package kz.courier.logisticsservice.service;

import kz.courier.logisticsservice.kafka.AssignmentEventPublisher;
import kz.courier.logisticsservice.grpc.OrderGrpcClient;
import kz.courier.logisticsservice.repository.AssignmentHistoryRepository;
import kz.courier.logisticsservice.repository.AssignmentRepository;
import kz.courier.logisticsservice.security.SystemPrincipalRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OfferTimeoutSchedulerTest {

    @Mock AssignmentRepository assignmentRepository;
    @Mock AssignmentHistoryRepository historyRepository;
    @Mock AssignmentEventPublisher eventPublisher;
    @Mock OrderGrpcClient orderGrpcClient;
    @Mock CapacityAwareAssignmentService assignmentService;
    @Mock RouteCleanupService routeCleanupService;
    @Mock SystemPrincipalRunner systemPrincipalRunner;
    @Mock AssignmentMetrics assignmentMetrics;

    @Test
    void timeoutBatchContinuesAfterOneItemFails() {
        UUID failingAssignmentId = UUID.randomUUID();
        UUID skippedAssignmentId = UUID.randomUUID();
        OfferTimeoutScheduler scheduler = new OfferTimeoutScheduler(
                assignmentRepository,
                historyRepository,
                eventPublisher,
                orderGrpcClient,
                assignmentService,
                routeCleanupService,
                systemPrincipalRunner,
                transactionManager(),
                assignmentMetrics);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "offerTimeoutSeconds", 300L);
        ReflectionTestUtils.setField(scheduler, "batchSize", 20);

        when(assignmentRepository.findTimedOutPendingOfferIds(any(OffsetDateTime.class), anyInt()))
                .thenReturn(List.of(failingAssignmentId, skippedAssignmentId));
        when(assignmentRepository.lockPendingOfferById(failingAssignmentId))
                .thenThrow(new IllegalStateException("database lock failed"));
        when(assignmentRepository.lockPendingOfferById(skippedAssignmentId))
                .thenReturn(Optional.empty());

        scheduler.timeoutPendingOffers();

        verify(assignmentRepository).lockPendingOfferById(failingAssignmentId);
        verify(assignmentRepository).lockPendingOfferById(skippedAssignmentId);
    }

    private PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
