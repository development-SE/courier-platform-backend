package kz.courier.logisticsservice.service;

import kz.courier.logisticsservice.dto.NearbycourierProjection;
import kz.courier.logisticsservice.dto.LogisticsDto;
import kz.courier.logisticsservice.entity.*;
import kz.courier.logisticsservice.grpc.OrderGrpcClient;
import kz.courier.logisticsservice.kafka.AssignmentEventPublisher;
import kz.courier.logisticsservice.mapper.AssignmentMapper;
import kz.courier.logisticsservice.repository.*;
import kz.courier.logisticsservice.security.GatewayPrincipalProvider;
import kz.courier.order.v1.OrderStatus;
import kz.courier.order.v1.ParcelSize;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CapacityAwareAssignmentServiceTest {

    @Mock AssignmentRepository assignmentRepository;
    @Mock AssignmentHistoryRepository historyRepository;
    @Mock CourierLocationRepository locationRepository;
    @Mock CourierRouteRepository routeRepository;
    @Mock RouteStopRepository stopRepository;
    @Mock AssignmentEventPublisher eventPublisher;
    @Mock OrderGrpcClient orderGrpcClient;
    @Mock GatewayPrincipalProvider gatewayPrincipalProvider;
    @Mock CourierProfileClient courierProfileClient;

    CapacityAwareAssignmentService service;

    UUID actorId;
    UUID orderId;

    @BeforeEach
    void setUp() {
        service = new CapacityAwareAssignmentService(
                assignmentRepository,
                historyRepository,
                locationRepository,
                routeRepository,
                stopRepository,
                new AssignmentMapper(),
                eventPublisher,
                orderGrpcClient,
                gatewayPrincipalProvider,
                courierProfileClient);
        actorId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        when(gatewayPrincipalProvider.requireCurrentUserId()).thenReturn(actorId);
        when(assignmentRepository.lockActiveAssignmentsByOrderId(orderId))
                .thenReturn(List.of(), List.of());
        lenient().when(orderGrpcClient.getOrder(orderId)).thenReturn(order(ParcelSize.SMALL));
        lenient().when(assignmentRepository.lockUnresolvedManualRequiredByOrderId(orderId))
                .thenReturn(Optional.empty());
        lenient().when(assignmentRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CourierAssignment assignment = invocation.getArgument(0);
            assignment.setId(UUID.randomUUID());
            assignment.setCreatedAt(OffsetDateTime.now());
            assignment.setUpdatedAt(OffsetDateTime.now());
            return assignment;
        });
    }

    @Test
    void selectsCourierWithEnoughCapacity() {
        UUID courierId = UUID.randomUUID();
        givenNearby(courierId, 43.01, 76.91, 150);
        givenProfile(courierId, "EMPLOYEE", "BIKE", 2);
        givenNoActiveRoute(courierId);
        givenCommit(courierId);

        LogisticsDto.AutoAssignResponse response = service.autoAssign(orderId);

        assertThat(response.assignmentStatus()).isEqualTo(AssignmentStatus.ASSIGNED);
        assertThat(response.courierId()).isEqualTo(courierId);
        assertThat(response.routeId()).isNotNull();
        verify(assignmentRepository).saveAndFlush(argThat(a ->
                a.getCourierId().equals(courierId)
                        && a.getDemandUnits() == 1
                        && a.getAssignmentPolicy() == AssignmentPolicy.DIRECT));
    }

    @Test
    void rejectsCourierWithInsufficientCapacity() {
        UUID courierId = UUID.randomUUID();
        when(orderGrpcClient.getOrder(orderId)).thenReturn(order(ParcelSize.LARGE));
        givenNearby(courierId, 43.01, 76.91, 150);
        givenProfile(courierId, "EMPLOYEE", "BIKE", 2);

        LogisticsDto.AutoAssignResponse response = service.autoAssign(orderId);

        assertThat(response.assignmentStatus()).isEqualTo(AssignmentStatus.MANUAL_REQUIRED);
        assertThat(response.failureReason()).isEqualTo(AssignmentFailureReason.NO_CAPACITY_AVAILABLE);
        verify(assignmentRepository).saveAndFlush(argThat(a ->
                a.getAssignmentStatus() == AssignmentStatus.MANUAL_REQUIRED
                        && a.getCourierId() == null
                        && a.getFailureReason() == AssignmentFailureReason.NO_CAPACITY_AVAILABLE));
    }

    @Test
    void lowerDistanceWins() {
        UUID closeCourier = UUID.randomUUID();
        UUID farCourier = UUID.randomUUID();
        givenNearby(List.of(
                projection(farCourier, 43.20, 77.20, 20_000),
                projection(closeCourier, 43.01, 76.91, 120)
        ));
        givenProfile(closeCourier, "EMPLOYEE", "CAR", 2);
        givenProfile(farCourier, "EMPLOYEE", "CAR", 2);
        givenNoActiveRoute(closeCourier);
        givenNoActiveRoute(farCourier);
        givenCommit(closeCourier);

        LogisticsDto.AutoAssignResponse response = service.autoAssign(orderId);

        assertThat(response.courierId()).isEqualTo(closeCourier);
    }

    @Test
    void duplicateAssignmentIsPrevented() {
        when(assignmentRepository.lockActiveAssignmentsByOrderId(orderId))
                .thenReturn(List.of(CourierAssignment.builder()
                        .orderId(orderId)
                        .courierId(UUID.randomUUID())
                        .assignmentStatus(AssignmentStatus.ASSIGNED)
                        .build()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.autoAssign(orderId))
                .hasMessageContaining("active assignment");
    }

    @Test
    void manualAssignmentRejectsAlreadyAssignedOrder() {
        UUID courierId = UUID.randomUUID();
        when(assignmentRepository.lockActiveAssignmentsByOrderId(orderId))
                .thenReturn(List.of(CourierAssignment.builder()
                        .orderId(orderId)
                        .courierId(courierId)
                        .assignmentStatus(AssignmentStatus.ASSIGNED)
                        .build()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.manualAssign(
                        new LogisticsDto.ManualAssignmentRequest(orderId, courierId, "dispatcher choice")))
                .hasMessageContaining("active assignment");
    }

    @Test
    void manualAssignmentRejectsInsufficientCapacity() {
        UUID courierId = UUID.randomUUID();
        when(orderGrpcClient.getOrder(orderId)).thenReturn(order(ParcelSize.LARGE));
        when(locationRepository.findById(courierId)).thenReturn(Optional.of(CourierLocation.builder()
                .courierId(courierId)
                .latitude(43.01)
                .longitude(76.91)
                .isOnline(true)
                .updatedAt(OffsetDateTime.now())
                .build()));
        givenProfile(courierId, "EMPLOYEE", "BIKE", 2);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.manualAssign(
                        new LogisticsDto.ManualAssignmentRequest(orderId, courierId, "dispatcher choice")))
                .hasMessageContaining("not eligible");
    }

    private OrderGrpcClient.OrderSnapshot order(ParcelSize parcelSize) {
        return new OrderGrpcClient.OrderSnapshot(
                orderId,
                OrderStatus.READY,
                43.00,
                76.90,
                43.05,
                76.95,
                parcelSize,
                1,
                "pickup");
    }

    private void givenNearby(UUID courierId, double lat, double lon, double distance) {
        givenNearby(List.of(projection(courierId, lat, lon, distance)));
    }

    private void givenNearby(List<NearbycourierProjection> projections) {
        when(locationRepository.findNearbyCouriers(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(projections);
    }

    private void givenProfile(UUID courierId, String courierType, String transportType, int maxActiveOrders) {
        when(courierProfileClient.getCourier(courierId)).thenReturn(Optional.of(
                new CourierProfileClient.CourierProfileSnapshot(
                        courierId,
                        courierType,
                        "ACTIVE",
                        transportType,
                        true,
                        true,
                        maxActiveOrders)));
    }

    private void givenNoActiveRoute(UUID courierId) {
        when(routeRepository.findByCourierIdAndStatus(courierId, RouteStatus.ACTIVE))
                .thenReturn(Optional.empty());
    }

    private void givenCommit(UUID courierId) {
        CourierLocation lockedLocation = CourierLocation.builder()
                .courierId(courierId)
                .latitude(43.01)
                .longitude(76.91)
                .isOnline(true)
                .updatedAt(OffsetDateTime.now())
                .build();
        when(locationRepository.lockByCourierId(courierId)).thenReturn(Optional.of(lockedLocation));
        when(routeRepository.lockActiveRouteByCourierId(courierId)).thenReturn(Optional.empty());
        when(routeRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CourierRoute route = invocation.getArgument(0);
            route.setId(UUID.randomUUID());
            return route;
        });
        when(routeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(stopRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(stopRepository).flush();
    }

    private NearbycourierProjection projection(UUID courierId, double lat, double lon, double distance) {
        Instant now = Instant.now();
        return new NearbycourierProjection() {
            public UUID getCourierId() { return courierId; }
            public Double getLatitude() { return lat; }
            public Double getLongitude() { return lon; }
            public Double getDistanceMeters() { return distance; }
            public Boolean getIsOnline() { return true; }
            public Instant getUpdatedAt() { return now; }
        };
    }
}
