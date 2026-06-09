package kz.courier.logisticsservice.service;

import kz.courier.logisticsservice.dto.LogisticsDto;
import kz.courier.logisticsservice.entity.AssignmentHistory;
import kz.courier.logisticsservice.entity.AssignmentStatus;
import kz.courier.logisticsservice.entity.CourierAssignment;
import kz.courier.logisticsservice.entity.CourierRoute;
import kz.courier.logisticsservice.entity.RouteStatus;
import kz.courier.logisticsservice.entity.RouteStop;
import kz.courier.logisticsservice.entity.RouteStopStatus;
import kz.courier.logisticsservice.entity.RouteStopType;
import kz.courier.logisticsservice.grpc.OrderGrpcClient;
import kz.courier.logisticsservice.kafka.AssignmentEventPublisher;
import kz.courier.logisticsservice.mapper.AssignmentMapper;
import kz.courier.logisticsservice.repository.AssignmentHistoryRepository;
import kz.courier.logisticsservice.repository.AssignmentRepository;
import kz.courier.logisticsservice.repository.CourierLocationRepository;
import kz.courier.logisticsservice.repository.CourierRouteRepository;
import kz.courier.logisticsservice.repository.RouteStopRepository;
import kz.courier.logisticsservice.security.GatewayPrincipalProvider;
import kz.courier.logisticsservice.security.SystemPrincipalRunner;
import kz.courier.order.v1.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LogisticsServiceTest {

    @Mock AssignmentRepository assignmentRepository;
    @Mock AssignmentHistoryRepository historyRepository;
    @Mock CourierLocationRepository locationRepository;
    @Mock CourierRouteRepository courierRouteRepository;
    @Mock RouteStopRepository routeStopRepository;
    @Mock AssignmentMapper mapper;
    @Mock AssignmentEventPublisher eventPublisher;
    @Mock OrderGrpcClient orderGrpcClient;
    @Mock GatewayPrincipalProvider gatewayPrincipalProvider;
    @Mock SystemPrincipalRunner systemPrincipalRunner;
    @Mock CapacityAwareAssignmentService capacityAwareAssignmentService;
    @Mock AssignmentRetryScheduler assignmentRetryScheduler;
    @Mock RouteCleanupService routeCleanupService;
    @Mock AssignmentMetrics assignmentMetrics;

    private LogisticsService service;
    private CourierAssignment assignment;
    private UUID assignmentId;
    private UUID orderId;
    private UUID courierId;
    private UUID routeId;

    @BeforeEach
    void setUp() {
        service = new LogisticsService(
                assignmentRepository,
                historyRepository,
                locationRepository,
                courierRouteRepository,
                routeStopRepository,
                mapper,
                eventPublisher,
                orderGrpcClient,
                gatewayPrincipalProvider,
                systemPrincipalRunner,
                capacityAwareAssignmentService,
                assignmentRetryScheduler,
                routeCleanupService,
                assignmentMetrics
        );

        assignmentId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        courierId = UUID.randomUUID();
        routeId = UUID.randomUUID();

        assignment = new CourierAssignment();
        assignment.setId(assignmentId);
        assignment.setOrderId(orderId);
        assignment.setCourierId(courierId);
        assignment.setRouteId(routeId);
        assignment.setAssignmentStatus(AssignmentStatus.ACCEPTED);
        
        when(gatewayPrincipalProvider.requireCurrentUserId()).thenReturn(courierId);
        lenient().when(routeCleanupService.cleanupAssignment(any(CourierAssignment.class), any(), anyBoolean()))
                .thenReturn(new RouteCleanupService.CleanupResult(assignmentId, orderId, routeId, false, 0, false, false, false));
    }

    @Test
    void updateStatusToPickedUpCompletesPickupStop() {
        // Arrange
        LogisticsDto.UpdateStatusRequest req = new LogisticsDto.UpdateStatusRequest(AssignmentStatus.PICKED_UP, courierId, "test");
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.save(any(CourierAssignment.class))).thenAnswer(i -> i.getArgument(0));

        RouteStop pickupStop = new RouteStop();
        pickupStop.setId(UUID.randomUUID());
        pickupStop.setStatus(RouteStopStatus.PENDING);
        
        when(routeStopRepository.findByRouteIdAndOrderIdAndStopTypeAndStatus(
                routeId, orderId, RouteStopType.PICKUP, RouteStopStatus.PENDING))
                .thenReturn(Optional.of(pickupStop));

        // Act
        service.updateStatus(assignmentId, req);

        // Assert
        assertEquals(AssignmentStatus.PICKED_UP, assignment.getAssignmentStatus());
        assertEquals(RouteStopStatus.COMPLETED, pickupStop.getStatus());
        verify(routeStopRepository).save(pickupStop);
    }

    @Test
    void verifyDeliveryCodeCompletesDropoffStop() {
        // Arrange
        assignment.setAssignmentStatus(AssignmentStatus.ARRIVED);
        LogisticsDto.VerifyDeliveryCodeRequest req = new LogisticsDto.VerifyDeliveryCodeRequest("1234");
        
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.save(any(CourierAssignment.class))).thenAnswer(i -> i.getArgument(0));
        when(orderGrpcClient.verifyDeliveryCode(orderId, "1234")).thenReturn(OrderStatus.DELIVERED);

        RouteStop dropoffStop = new RouteStop();
        dropoffStop.setId(UUID.randomUUID());
        dropoffStop.setStatus(RouteStopStatus.PENDING);
        
        when(routeStopRepository.findByRouteIdAndOrderIdAndStopTypeAndStatus(
                routeId, orderId, RouteStopType.DROPOFF, RouteStopStatus.PENDING))
                .thenReturn(Optional.of(dropoffStop));

        // Act
        service.verifyDeliveryCode(assignmentId, req);

        // Assert
        assertEquals(AssignmentStatus.DELIVERED, assignment.getAssignmentStatus());
        assertEquals(RouteStopStatus.COMPLETED, dropoffStop.getStatus());
        verify(routeStopRepository).save(dropoffStop);
    }

    @Test
    void missingRouteStopDoesNotFailAssignmentStatusUpdate() {
        // Arrange: status update should succeed even if stop isn't found
        LogisticsDto.UpdateStatusRequest req = new LogisticsDto.UpdateStatusRequest(AssignmentStatus.PICKED_UP, courierId, "test");
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.save(any(CourierAssignment.class))).thenAnswer(i -> i.getArgument(0));
        
        when(routeStopRepository.findByRouteIdAndOrderIdAndStopTypeAndStatus(
                routeId, orderId, RouteStopType.PICKUP, RouteStopStatus.PENDING))
                .thenReturn(Optional.empty()); // No stop found

        // Act
        assertDoesNotThrow(() -> service.updateStatus(assignmentId, req));

        // Assert
        assertEquals(AssignmentStatus.PICKED_UP, assignment.getAssignmentStatus());
        verify(routeStopRepository, never()).save(any(RouteStop.class));
    }

    @Test
    void nullRouteIdFallsBackToActiveRouteLookup() {
        // Arrange
        assignment.setRouteId(null); // Emulate assignment without a direct routeId link
        LogisticsDto.UpdateStatusRequest req = new LogisticsDto.UpdateStatusRequest(AssignmentStatus.PICKED_UP, courierId, "test");
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.save(any(CourierAssignment.class))).thenAnswer(i -> i.getArgument(0));

        CourierRoute activeRoute = new CourierRoute();
        activeRoute.setId(routeId);
        
        // Mock fallback active route lookup
        when(courierRouteRepository.findByCourierIdAndStatus(courierId, RouteStatus.ACTIVE))
                .thenReturn(Optional.of(activeRoute));

        RouteStop pickupStop = new RouteStop();
        pickupStop.setId(UUID.randomUUID());
        pickupStop.setStatus(RouteStopStatus.PENDING);
        
        when(routeStopRepository.findByRouteIdAndOrderIdAndStopTypeAndStatus(
                routeId, orderId, RouteStopType.PICKUP, RouteStopStatus.PENDING))
                .thenReturn(Optional.of(pickupStop));

        // Act
        service.updateStatus(assignmentId, req);

        // Assert
        verify(courierRouteRepository).findByCourierIdAndStatus(courierId, RouteStatus.ACTIVE);
        assertEquals(RouteStopStatus.COMPLETED, pickupStop.getStatus());
        verify(routeStopRepository).save(pickupStop);
    }

    @Test
    void noActiveRouteIgnoresStopCompletionGracefully() {
        // Arrange
        assignment.setRouteId(null);
        LogisticsDto.UpdateStatusRequest req = new LogisticsDto.UpdateStatusRequest(AssignmentStatus.PICKED_UP, courierId, "test");
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.save(any(CourierAssignment.class))).thenAnswer(i -> i.getArgument(0));

        // No active route found for the courier
        when(courierRouteRepository.findByCourierIdAndStatus(courierId, RouteStatus.ACTIVE))
                .thenReturn(Optional.empty());

        // Act
        assertDoesNotThrow(() -> service.updateStatus(assignmentId, req));

        // Assert
        assertEquals(AssignmentStatus.PICKED_UP, assignment.getAssignmentStatus());
        verify(routeStopRepository, never()).findByRouteIdAndOrderIdAndStopTypeAndStatus(any(), any(), any(), any());
        verify(routeStopRepository, never()).save(any(RouteStop.class));
    }
}
