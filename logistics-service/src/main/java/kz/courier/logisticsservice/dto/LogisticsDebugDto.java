package kz.courier.logisticsservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import kz.courier.logisticsservice.entity.AssignmentPolicy;
import kz.courier.logisticsservice.entity.AssignmentStatus;
import kz.courier.logisticsservice.entity.RouteStatus;
import kz.courier.logisticsservice.entity.RouteStopStatus;
import kz.courier.logisticsservice.entity.RouteStopType;
import kz.courier.order.v1.OrderStatus;
import kz.courier.order.v1.ParcelSize;
import kz.courier.order.v1.ServiceType;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class LogisticsDebugDto {

    private LogisticsDebugDto() {}

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssignmentMapSnapshotResponse(
            OffsetDateTime generatedAt,
            AssignmentMapSummaryDto summary,
            ScopeInfoDto scope,
            DataSourcesDto dataSources,
            List<CourierMapDto> couriers,
            List<OrderMapDto> orders,
            List<RouteMapDto> routes,
            List<AssignmentMapDto> assignments,
            UUID selectedOrderId,
            UUID selectedAssignmentId
    ) {}

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssignmentMapSummaryDto(
            Integer totalCouriers,
            Integer onlineCouriers,
            Integer availableCouriers,
            Integer busyCouriers,
            Integer totalOrders,
            Integer unassignedReadyOrders,
            Integer assignmentPendingOrders,
            Integer assignedOrders,
            Integer pendingOffers,
            Integer manualRequiredAssignments,
            Integer timedOutAssignments,
            Integer rejectedAssignments,
            Integer activeRoutes,
            Integer completedRoutes
    ) {}

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScopeInfoDto(
            String cityScope,
            Double minLat,
            Double minLng,
            Double maxLat,
            Double maxLng,
            Double centerLat,
            Double centerLng,
            Integer zoom
    ) {}

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DataSourcesDto(
            String couriers,
            String orders,
            String routes,
            String assignments,
            String simulation
    ) {}

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CourierMapDto(
            UUID courierId,
            String name,
            String displayName,
            String identitySource,
            String courierType,
            UUID companyId,
            Double lat,
            Double lng,
            Boolean online,
            Boolean available,
            String employmentStatus,
            Integer currentLoad,
            Integer maxCapacity,
            Integer activeOrders,
            String transportType,
            List<UUID> activeAssignmentIds,
            List<UUID> assignedOrderIds,
            String availabilityStatus,
            Long locationAgeSeconds,
            UUID currentRouteId,
            UUID nextStopId,
            RouteStopType nextStopType,
            Double distanceToNextStopMeters,
            String progressState
    ) {}

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrderMapDto(
            UUID orderId,
            OrderStatus status,
            ServiceType serviceType,
            UUID companyId,
            ParcelSize parcelSize,
            Double pickupLat,
            Double pickupLng,
            Double deliveryLat,
            Double deliveryLng,
            UUID assignedCourierId,
            String assignmentState,
            UUID activeAssignmentId,
            AssignmentPolicy assignmentPolicy,
            AssignmentStatus assignmentStatus,
            String unassignedReason,
            Boolean otpRequired,
            Boolean waitingForOtp,
            Boolean canConfirmDelivery,
            String progressState
    ) {}

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RouteMapDto(
            UUID routeId,
            UUID courierId,
            RouteStatus status,
            Integer currentLoad,
            Integer maxCapacity,
            Integer activeOrders,
            List<RouteStopMapDto> stops
    ) {}

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RouteStopMapDto(
            UUID stopId,
            Integer sequence,
            UUID orderId,
            RouteStopType type,
            RouteStopStatus status,
            Double lat,
            Double lng,
            Integer demandChange
    ) {}

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssignmentMapDto(
            UUID assignmentId,
            UUID orderId,
            UUID courierId,
            AssignmentStatus status,
            AssignmentPolicy policy,
            Double score,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CandidatePreviewResponse(
            UUID orderId,
            UUID winnerCourierId,
            List<CandidatePreviewDto> candidates
    ) {}

    public record SimulationStartRequest(
            List<UUID> courierIds,
            List<UUID> assignmentIds,
            List<UUID> routeIds,
            String scenarioName,
            Boolean allowAllActiveRoutes
    ) {}

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SimulationStatusResponse(
            Boolean running,
            String progressState,
            String blockedReason,
            OffsetDateTime startedAt,
            OffsetDateTime lastStepAt,
            List<UUID> courierIds,
            List<UUID> assignmentIds,
            List<UUID> routeIds,
            String scenarioName,
            Boolean allowAllActiveRoutes,
            UUID currentRouteId,
            UUID currentStopId,
            RouteStopType currentStopType,
            UUID currentAssignmentId,
            UUID currentOrderId,
            Boolean otpRequired,
            Boolean waitingForOtp,
            Boolean canConfirmDelivery,
            Double distanceToNextStopMeters
    ) {}

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CandidatePreviewDto(
            UUID courierId,
            String courierType,
            UUID companyId,
            Boolean eligible,
            Double score,
            Double distanceMeters,
            Double addedDistanceMeters,
            Integer etaSeconds,
            Boolean capacityOk,
            Boolean employeePriorityApplied,
            AssignmentPolicy policy,
            List<String> reasons,
            List<RouteStopMapDto> previewStops
    ) {}
}
