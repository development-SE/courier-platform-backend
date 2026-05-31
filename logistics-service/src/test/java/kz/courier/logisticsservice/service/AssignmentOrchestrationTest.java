package kz.courier.logisticsservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.courier.logisticsservice.entity.AssignmentFailureReason;
import kz.courier.logisticsservice.entity.AssignmentStatus;
import kz.courier.logisticsservice.entity.CourierAssignment;
import kz.courier.logisticsservice.kafka.OrderCreatedEventListener;
import kz.courier.logisticsservice.repository.AssignmentRepository;
import kz.courier.logisticsservice.security.SystemPrincipalRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AssignmentOrchestrationTest {

    @Mock CapacityAwareAssignmentService assignmentService;
    @Mock SystemPrincipalRunner systemPrincipalRunner;
    @Mock AssignmentRepository assignmentRepository;

    @Test
    void orderCreatedListenerCallsAutoAssignment() {
        UUID orderId = UUID.randomUUID();
        OrderCreatedEventListener listener = new OrderCreatedEventListener(
                new ObjectMapper(), assignmentService, systemPrincipalRunner);
        when(assignmentService.hasActiveAssignment(orderId)).thenReturn(false);
        when(systemPrincipalRunner.run(any())).thenAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(0);
            return supplier.get();
        });

        listener.onOrderCreated("""
                {"eventType":"ORDER_CREATED","orderId":"%s","status":"READY","serviceType":"STANDARD"}
                """.formatted(orderId));

        verify(assignmentService).autoAssign(orderId);
    }

    @Test
    void orderCreatedListenerIgnoresAlreadyAssignedOrders() {
        UUID orderId = UUID.randomUUID();
        OrderCreatedEventListener listener = new OrderCreatedEventListener(
                new ObjectMapper(), assignmentService, systemPrincipalRunner);
        when(assignmentService.hasActiveAssignment(orderId)).thenReturn(true);

        listener.onOrderCreated("""
                {"eventType":"ORDER_CREATED","orderId":"%s","status":"READY","serviceType":"STANDARD"}
                """.formatted(orderId));

        verify(assignmentService, never()).autoAssign(any());
    }

//    @Test
//    void schedulerRetriesTemporaryReasonsAndSkipsPermanentReasonsByQuery() {
//        AssignmentRetryScheduler scheduler = new AssignmentRetryScheduler(
//                assignmentRepository, assignmentService, systemPrincipalRunner);
//        ReflectionTestUtils.setField(scheduler, "enabled", true);
//        ReflectionTestUtils.setField(scheduler, "maxAttempts", 5);
//        ReflectionTestUtils.setField(scheduler, "batchSize", 10);
//        ReflectionTestUtils.setField(scheduler, "retryDelaySeconds", 60L);
//
//        UUID orderId = UUID.randomUUID();
//        CourierAssignment failed = CourierAssignment.builder()
//                .id(UUID.randomUUID())
//                .orderId(orderId)
//                .assignmentStatus(AssignmentStatus.MANUAL_REQUIRED)
//                .failureReason(AssignmentFailureReason.NO_ONLINE_COURIERS)
//                .retryCount(0)
//                .assignedAt(OffsetDateTime.now())
//                .build();
//        when(assignmentRepository.lockDueManualRequiredRetries(anyList(), anyInt(), anyInt()))
//                .thenReturn(List.of(failed));
//        when(assignmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
//        when(systemPrincipalRunner.run(any())).thenAnswer(invocation -> {
//            Supplier<?> supplier = invocation.getArgument(0);
//            return supplier.get();
//        });
//        when(assignmentService.autoAssign(orderId)).thenReturn(
//                kz.courier.logisticsservice.dto.LogisticsDto.AutoAssignResponse.builder()
//                        .orderId(orderId)
//                        .assignmentStatus(AssignmentStatus.MANUAL_REQUIRED)
//                        .failureReason(AssignmentFailureReason.NO_ONLINE_COURIERS)
//                        .message("still waiting")
//                        .build());
//
//        scheduler.retryDueManualRequiredAssignments();
//
//        verify(assignmentRepository).lockDueManualRequiredRetries(
//                argThat(reasons -> reasons.contains("NO_ONLINE_COURIERS")
//                        && !reasons.contains("MISSING_ORDER_COORDINATES")),
//                eq(5),
//                eq(10));
//        verify(assignmentService).autoAssign(orderId);
//    }
}
