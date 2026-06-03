package kz.courier.logisticsservice.service;

import kz.courier.logisticsservice.kafka.AssignmentEventPublisher;
import kz.courier.logisticsservice.grpc.OrderGrpcClient;
import kz.courier.logisticsservice.repository.AssignmentHistoryRepository;
import kz.courier.logisticsservice.repository.AssignmentRepository;
import kz.courier.logisticsservice.security.SystemPrincipalRunner;
import kz.courier.logisticsservice.entity.AssignmentPolicy;
import kz.courier.logisticsservice.entity.AssignmentStatus;
import kz.courier.logisticsservice.entity.CourierAssignment;
import kz.courier.logisticsservice.dto.LogisticsDto;
import kz.courier.order.v1.OrderStatus;
import kz.courier.order.v1.ParcelSize;
import kz.courier.order.v1.ServiceType;
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

    @Test
    void should_MarkTimedOutCleanupAndReassign_When_PendingOfferExpires() {
        UUID assignmentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        CourierAssignment assignment = CourierAssignment.builder()
                .id(assignmentId)
                .orderId(orderId)
                .courierId(courierId)
                .assignmentPolicy(AssignmentPolicy.OFFER)
                .assignmentStatus(AssignmentStatus.PENDING)
                .assignedAt(OffsetDateTime.now().minusMinutes(10))
                .build();
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
                .thenReturn(List.of(assignmentId));
        when(assignmentRepository.lockPendingOfferById(assignmentId)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.save(any(CourierAssignment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(systemPrincipalRunner.run(any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> supplier = invocation.getArgument(0);
            return supplier.get();
        });
        when(orderGrpcClient.getOrder(orderId)).thenReturn(new OrderGrpcClient.OrderSnapshot(
                orderId,
                OrderStatus.READY,
                ServiceType.STANDARD,
                null,
                43.0,
                76.9,
                43.1,
                77.0,
                ParcelSize.SMALL,
                1,
                "pickup",
                OffsetDateTime.now().minusMinutes(5)));
        when(assignmentService.autoAssign(orderId)).thenReturn(LogisticsDto.AutoAssignResponse.builder()
                .orderId(orderId)
                .courierId(UUID.randomUUID())
                .assignmentStatus(AssignmentStatus.ASSIGNED)
                .message("reassigned")
                .build());

        scheduler.timeoutPendingOffers();

        org.assertj.core.api.Assertions.assertThat(assignment.getAssignmentStatus()).isEqualTo(AssignmentStatus.TIMED_OUT);
        verify(routeCleanupService).cleanupAssignment(assignment, "offer-timeout", false);
        verify(eventPublisher).publishStatusChanged(
                assignmentId, orderId, courierId, AssignmentStatus.PENDING, AssignmentStatus.TIMED_OUT);
        verify(orderGrpcClient).markAssignmentPending(orderId);
        verify(assignmentService).autoAssign(orderId);
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
