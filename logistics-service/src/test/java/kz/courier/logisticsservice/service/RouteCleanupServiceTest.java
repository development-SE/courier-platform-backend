package kz.courier.logisticsservice.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import kz.courier.logisticsservice.entity.AssignmentStatus;
import kz.courier.logisticsservice.entity.CourierAssignment;
import kz.courier.logisticsservice.entity.CourierRoute;
import kz.courier.logisticsservice.entity.RouteStatus;
import kz.courier.logisticsservice.entity.RouteStop;
import kz.courier.logisticsservice.entity.RouteStopStatus;
import kz.courier.logisticsservice.entity.RouteStopType;
import kz.courier.logisticsservice.grpc.OrderGrpcClient;
import kz.courier.logisticsservice.kafka.AssignmentEventPublisher;
import kz.courier.logisticsservice.repository.AssignmentRepository;
import kz.courier.logisticsservice.repository.CourierRouteRepository;
import kz.courier.logisticsservice.repository.RouteStopRepository;
import kz.courier.logisticsservice.security.GatewayPrincipalProvider;
import kz.courier.logisticsservice.security.SystemPrincipalRunner;
import kz.courier.order.v1.OrderStatus;
import kz.courier.order.v1.ParcelSize;
import kz.courier.order.v1.ServiceType;

@ExtendWith(MockitoExtension.class)
class RouteCleanupServiceTest {

    @Mock AssignmentRepository assignmentRepository;
    @Mock CourierRouteRepository routeRepository;
    @Mock RouteStopRepository routeStopRepository;
    @Mock OrderGrpcClient orderGrpcClient;
    @Mock AssignmentEventPublisher eventPublisher;
    @Mock SystemPrincipalRunner systemPrincipalRunner;
    @Mock GatewayPrincipalProvider gatewayPrincipalProvider;
    @Mock AssignmentMetrics assignmentMetrics;

    RouteCleanupService service;
    UUID assignmentId;
    UUID orderId;
    UUID routeId;
    CourierAssignment assignment;
    CourierRoute route;
    List<RouteStop> stops;

    @BeforeEach
    void setUp() {
        service = new RouteCleanupService(
                assignmentRepository,
                routeRepository,
                routeStopRepository,
                eventPublisher,
                gatewayPrincipalProvider,
                assignmentMetrics);
        assignmentId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        routeId = UUID.randomUUID();
        assignment = CourierAssignment.builder()
                .id(assignmentId)
                .orderId(orderId)
                .routeId(routeId)
                .demandUnits(4)
                .assignmentStatus(AssignmentStatus.REJECTED)
                .assignedAt(OffsetDateTime.now())
                .build();
        route = CourierRoute.builder()
                .id(routeId)
                .courierId(UUID.randomUUID())
                .status(RouteStatus.ACTIVE)
                .currentLoadUnits(10)
                .maxCapacityUnits(20)
                .activeOrdersCount(3)
                .build();
        stops = List.of(
                stop(RouteStopStatus.PENDING),
                stop(RouteStopStatus.ARRIVED),
                stop(RouteStopStatus.COMPLETED));

        when(assignmentRepository.lockById(assignmentId)).thenReturn(Optional.of(assignment));
        when(routeRepository.lockById(routeId)).thenReturn(Optional.of(route));
        when(routeStopRepository.findAllByRouteIdOrderBySequenceNumberAsc(routeId)).thenReturn(stops);
        when(routeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(assignmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(routeStopRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void cleanupCalledTwiceDoesNotDoubleReleaseCapacityOrActiveOrders() {
        RouteCleanupService.CleanupResult first = service.cleanupAssignment(assignmentId, "test", false);
        RouteCleanupService.CleanupResult second = service.cleanupAssignment(assignmentId, "test", false);

        assertThat(first.capacityReleased()).isTrue();
        assertThat(first.stopsCancelled()).isEqualTo(2);
        assertThat(first.routeCompleted()).isTrue();
        assertThat(first.alreadyCleaned()).isFalse();
        assertThat(second.capacityReleased()).isFalse();
        assertThat(second.stopsCancelled()).isZero();
        assertThat(second.alreadyCleaned()).isTrue();
        assertThat(route.getCurrentLoadUnits()).isEqualTo(6);
        assertThat(route.getActiveOrdersCount()).isEqualTo(2);
    }

    @Test
    void cleanupPreservesCompletedStopsAndCancelsOnlyUncompletedStops() {
        service.cleanupAssignment(assignmentId, "test", false);

        assertThat(stops.get(0).getStatus()).isEqualTo(RouteStopStatus.CANCELLED);
        assertThat(stops.get(1).getStatus()).isEqualTo(RouteStopStatus.CANCELLED);
        assertThat(stops.get(2).getStatus()).isEqualTo(RouteStopStatus.COMPLETED);
    }

    @Test
    void cancelledOrderCleanupCancelsActiveAssignmentAndDoesNotRequestReassignment() {
        assignment.setAssignmentStatus(AssignmentStatus.ASSIGNED);
        when(assignmentRepository.lockUncleanedAssignmentsByOrderId(orderId)).thenReturn(List.of(assignment));

        List<RouteCleanupService.CleanupResult> results =
                service.cleanupOrderCancellation(orderId, "order-cancelled-event");

        assertThat(results).hasSize(1);
        assertThat(assignment.getAssignmentStatus()).isEqualTo(AssignmentStatus.CANCELLED);
        assertThat(results.getFirst().reassignmentRequested()).isFalse();
        verify(eventPublisher).publishStatusChanged(
                assignmentId, orderId, assignment.getCourierId(), AssignmentStatus.ASSIGNED, AssignmentStatus.CANCELLED);
        verify(orderGrpcClient, never()).markAssignmentPending(any());
    }

    @Test
    void cleanupDoesNotRequestReassignmentForCancelledOrder() {
        when(systemPrincipalRunner.run(any())).thenAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(0);
            return supplier.get();
        });
        when(orderGrpcClient.getOrder(orderId)).thenReturn(order(OrderStatus.CANCELLED));

        RouteCleanupService.CleanupResult result = service.cleanupAssignment(assignmentId, "test", true);

        assertThat(result.reassignmentRequested()).isFalse();
        verify(orderGrpcClient, never()).markAssignmentPending(any());
    }

    @Test
    void cleanupReportsEligibleReassignmentWithoutMarkingAssignmentPending() {
        when(systemPrincipalRunner.run(any())).thenAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(0);
            return supplier.get();
        });
        when(orderGrpcClient.getOrder(orderId)).thenReturn(order(OrderStatus.READY));

        RouteCleanupService.CleanupResult result = service.cleanupAssignment(assignmentId, "test", true);

        assertThat(result.reassignmentRequested()).isTrue();
        verify(orderGrpcClient, never()).markAssignmentPending(any());
    }

    private RouteStop stop(RouteStopStatus status) {
        return RouteStop.builder()
                .id(UUID.randomUUID())
                .routeId(routeId)
                .orderId(orderId)
                .stopType(RouteStopType.PICKUP)
                .sequenceNumber(1)
                .latitude(43.0)
                .longitude(76.0)
                .status(status)
                .build();
    }

    private OrderGrpcClient.OrderSnapshot order(OrderStatus status) {
        return new OrderGrpcClient.OrderSnapshot(
                orderId,
                status,
                ServiceType.STANDARD,
                null,
                43.0,
                76.0,
                43.1,
                76.1,
                ParcelSize.SMALL,
                1,
                "pickup",
                OffsetDateTime.now());
    }
}
