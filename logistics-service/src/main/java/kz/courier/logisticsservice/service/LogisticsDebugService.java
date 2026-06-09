package kz.courier.logisticsservice.service;

import kz.courier.logisticsservice.dto.LogisticsDebugDto;
import kz.courier.logisticsservice.entity.AssignmentPolicy;
import kz.courier.logisticsservice.entity.AssignmentStatus;
import kz.courier.logisticsservice.entity.CourierAssignment;
import kz.courier.logisticsservice.entity.CourierLocation;
import kz.courier.logisticsservice.entity.CourierRoute;
import kz.courier.logisticsservice.entity.RouteStatus;
import kz.courier.logisticsservice.entity.RouteStop;
import kz.courier.logisticsservice.entity.RouteStopStatus;
import kz.courier.logisticsservice.entity.RouteStopType;
import kz.courier.logisticsservice.exception.BusinessException;
import kz.courier.logisticsservice.grpc.OrderGrpcClient;
import kz.courier.logisticsservice.repository.AssignmentRepository;
import kz.courier.logisticsservice.repository.AssignmentHistoryRepository;
import kz.courier.logisticsservice.repository.CourierLocationRepository;
import kz.courier.logisticsservice.repository.CourierRouteRepository;
import kz.courier.logisticsservice.repository.RouteStopRepository;
import kz.courier.order.v1.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogisticsDebugService {

    private static final int DEFAULT_ZOOM = 12;
    private static final double ARRIVAL_THRESHOLD_METERS = 25.0;
    private static final double STEP_DISTANCE_METERS = 180.0;
    private static final Set<AssignmentStatus> ACTIVE_ASSIGNMENT_STATUSES = Set.of(
            AssignmentStatus.PENDING,
            AssignmentStatus.ASSIGNED,
            AssignmentStatus.ACCEPTED,
            AssignmentStatus.PICKED_UP,
            AssignmentStatus.IN_TRANSIT,
            AssignmentStatus.ARRIVED);
    private static final Set<OrderStatus> OPERATIONS_ORDER_STATUSES = Set.of(
            OrderStatus.READY,
            OrderStatus.ASSIGNMENT_PENDING,
            OrderStatus.ASSIGNED,
            OrderStatus.PICKED_UP,
            OrderStatus.IN_TRANSIT,
            OrderStatus.DELIVERY_CONFIRMATION_PENDING);

    private final CourierLocationRepository locationRepository;
    private final CourierRouteRepository routeRepository;
    private final RouteStopRepository stopRepository;
    private final AssignmentRepository assignmentRepository;
    private final CourierProfileClient courierProfileClient;
    private final OrderGrpcClient orderGrpcClient;
    private final AssignmentHistoryRepository historyRepository;

    private volatile SimulationSession simulationSession = SimulationSession.stopped();

    @Transactional(readOnly = true)
    public LogisticsDebugDto.AssignmentMapSnapshotResponse getAssignmentMap(
            UUID orderId,
            String scenario,
            String cityScope,
            Double customMinLat,
            Double customMinLng,
            Double customMaxLat,
            Double customMaxLng,
            int maxCouriers,
            int maxRoutes,
            int maxAssignments) {

        ScopeBounds bounds = resolveBounds(cityScope, customMinLat, customMinLng, customMaxLat, customMaxLng);
        List<CourierLocation> locations = locationRepository
                .findAllWithinBounds(bounds.minLat(), bounds.minLng(), bounds.maxLat(), bounds.maxLng())
                .stream()
                .limit(Math.max(1, maxCouriers))
                .toList();

        List<CourierRoute> allRoutes = routeRepository.findAll();
        List<CourierAssignment> allAssignments = assignmentRepository.findAll();
        Map<UUID, List<CourierAssignment>> activeAssignmentsByCourier = allAssignments.stream()
                .filter(a -> ACTIVE_ASSIGNMENT_STATUSES.contains(a.getAssignmentStatus()))
                .filter(a -> a.getCourierId() != null)
                .collect(Collectors.groupingBy(CourierAssignment::getCourierId));
        Map<UUID, CourierRoute> activeRouteByCourier = allRoutes.stream()
                .filter(r -> r.getStatus() == RouteStatus.ACTIVE)
                .collect(Collectors.toMap(CourierRoute::getCourierId, Function.identity(), (left, right) -> left));

        List<LogisticsDebugDto.CourierMapDto> couriers = locations.stream()
                .map(loc -> toCourierDto(loc, activeAssignmentsByCourier, activeRouteByCourier))
                .filter(java.util.Objects::nonNull)
                .toList();

        Set<UUID> inScopeCourierIds = couriers.stream()
                .map(LogisticsDebugDto.CourierMapDto::courierId)
                .collect(Collectors.toSet());

        List<CourierRoute> visibleRoutes = allRoutes.stream()
                .filter(r -> inScopeCourierIds.contains(r.getCourierId()) || r.getStatus() == RouteStatus.ACTIVE)
                .sorted(Comparator.comparing(CourierRoute::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(1, maxRoutes))
                .toList();

        Map<UUID, List<RouteStop>> routeStops = visibleRoutes.stream()
                .collect(Collectors.toMap(CourierRoute::getId,
                        route -> stopRepository.findAllByRouteIdOrderBySequenceNumberAsc(route.getId())));

        List<LogisticsDebugDto.RouteMapDto> routes = visibleRoutes.stream()
                .map(route -> toRouteDto(route, routeStops.getOrDefault(route.getId(), List.of())))
                .toList();

        List<CourierAssignment> visibleAssignments = allAssignments.stream()
                .filter(a -> shouldShowAssignment(a, inScopeCourierIds, orderId))
                .sorted(Comparator.comparing(CourierAssignment::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(1, maxAssignments))
                .toList();

        List<LogisticsDebugDto.AssignmentMapDto> assignments = visibleAssignments.stream()
                .map(this::toAssignmentDto)
                .toList();

        Map<UUID, CourierAssignment> activeAssignmentByOrder = allAssignments.stream()
                .filter(a -> ACTIVE_ASSIGNMENT_STATUSES.contains(a.getAssignmentStatus())
                        || a.getAssignmentStatus() == AssignmentStatus.MANUAL_REQUIRED)
                .collect(Collectors.toMap(CourierAssignment::getOrderId, Function.identity(), (left, right) -> left));

        List<OrderGrpcClient.OrderSnapshot> orderSnapshots = loadVisibleOrders(
                orderId, visibleAssignments, routeStops.values().stream().flatMap(Collection::stream).toList(), bounds, maxAssignments);

        List<LogisticsDebugDto.OrderMapDto> orders = orderSnapshots.stream()
                .filter(order -> isOrderInBounds(order, bounds))
                .map(order -> toOrderDto(order, activeAssignmentByOrder.get(order.orderId())))
                .toList();

        LogisticsDebugDto.AssignmentMapSummaryDto summary = buildSummary(couriers, orders, allAssignments, allRoutes);

        UUID selectedAssignmentId = orderId == null || activeAssignmentByOrder.get(orderId) == null
                ? null
                : activeAssignmentByOrder.get(orderId).getId();

        return LogisticsDebugDto.AssignmentMapSnapshotResponse.builder()
                .generatedAt(OffsetDateTime.now())
                .summary(summary)
                .scope(bounds.toDto())
                .dataSources(LogisticsDebugDto.DataSourcesDto.builder()
                        .couriers("logistics-service.courier_locations + courier-service profiles")
                        .orders("order-service gRPC ListOrders/GetOrder")
                        .routes("logistics-service.courier_routes + route_stops")
                        .assignments("logistics-service.courier_assignments")
                        .simulation("logistics-service debug simulation status")
                        .build())
                .couriers(couriers)
                .orders(orders)
                .routes(routes)
                .assignments(assignments)
                .selectedOrderId(orderId)
                .selectedAssignmentId(selectedAssignmentId)
                .build();
    }

    @Transactional
    public LogisticsDebugDto.SimulationStatusResponse startSimulation(LogisticsDebugDto.SimulationStartRequest request) {
        List<UUID> courierIds = cleanIds(request == null ? null : request.courierIds());
        List<UUID> assignmentIds = cleanIds(request == null ? null : request.assignmentIds());
        List<UUID> routeIds = cleanIds(request == null ? null : request.routeIds());
        String scenarioName = request == null ? null : blankToNull(request.scenarioName());
        boolean allowAllActiveRoutes = request != null && Boolean.TRUE.equals(request.allowAllActiveRoutes());

        if (courierIds.isEmpty() && assignmentIds.isEmpty() && routeIds.isEmpty()
                && scenarioName == null && !allowAllActiveRoutes) {
            throw new BusinessException("SIMULATION_SCOPE_REQUIRED",
                    "Select courierIds, assignmentIds, routeIds, scenarioName, or set allowAllActiveRoutes=true");
        }

        List<UUID> effectiveRouteIds = new ArrayList<>(routeIds);
        if (allowAllActiveRoutes) {
            routeRepository.findAll().stream()
                    .filter(route -> route.getStatus() == RouteStatus.ACTIVE)
                    .map(CourierRoute::getId)
                    .forEach(effectiveRouteIds::add);
        }
        for (UUID courierId : courierIds) {
            routeRepository.findByCourierIdAndStatus(courierId, RouteStatus.ACTIVE)
                    .map(CourierRoute::getId)
                    .ifPresent(effectiveRouteIds::add);
        }
        for (UUID assignmentId : assignmentIds) {
            assignmentRepository.findById(assignmentId)
                    .map(CourierAssignment::getRouteId)
                    .filter(Objects::nonNull)
                    .ifPresent(effectiveRouteIds::add);
        }

        simulationSession = new SimulationSession(
                true,
                "RUNNING",
                null,
                OffsetDateTime.now(),
                null,
                distinct(courierIds),
                distinct(assignmentIds),
                distinct(effectiveRouteIds),
                scenarioName,
                allowAllActiveRoutes,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                false,
                null);
        return getSimulationStatus();
    }

    @Transactional
    public LogisticsDebugDto.SimulationStatusResponse stepSimulation() {
        SimulationSession session = simulationSession;
        if (!session.running()) {
            return getSimulationStatus();
        }

        Optional<CourierRoute> routeOpt = selectSimulationRoute(session);
        if (routeOpt.isEmpty()) {
            simulationSession = session.withBlocked("NO_ACTIVE_ROUTE", null, null, null, null, null, false, false, false, null);
            return getSimulationStatus();
        }

        CourierRoute route = routeOpt.get();
        Optional<RouteStop> stopOpt = stopRepository.findFirstByRouteIdAndStatusOrderBySequenceNumberAsc(
                route.getId(), RouteStopStatus.PENDING);
        if (stopOpt.isEmpty()) {
            simulationSession = session.withBlocked("NO_PENDING_ROUTE_STOP", route.getId(), null, null, null, null, false, false, false, null);
            return getSimulationStatus();
        }

        RouteStop stop = stopOpt.get();
        CourierLocation location = locationRepository.findById(route.getCourierId())
                .orElseGet(() -> CourierLocation.builder()
                        .courierId(route.getCourierId())
                        .latitude(stop.getLatitude())
                        .longitude(stop.getLongitude())
                        .updatedAt(OffsetDateTime.now())
                        .isOnline(true)
                        .build());

        double distance = distanceMeters(location.getLatitude(), location.getLongitude(), stop.getLatitude(), stop.getLongitude());
        CourierAssignment assignment = findActiveAssignmentForStop(route, stop).orElse(null);
        boolean otpRequired = stop.getStopType() == RouteStopType.DROPOFF;
        boolean waitingForOtp = assignment != null && assignment.getAssignmentStatus() == AssignmentStatus.ARRIVED;

        if (distance <= ARRIVAL_THRESHOLD_METERS) {
            AssignmentStatus nextStatus = nextLifecycleStatus(assignment, stop);
            if (nextStatus == null) {
                simulationSession = session.withBlocked("LIFECYCLE_METHOD_UNAVAILABLE",
                        route.getId(), stop.getId(), stop.getStopType(),
                        assignment == null ? null : assignment.getId(),
                        stop.getOrderId(), otpRequired, waitingForOtp, waitingForOtp, distance);
                return getSimulationStatus();
            }

            simulationSession = session.withBlocked("ROUTE_STOP_COMPLETION_METHOD_UNAVAILABLE",
                    route.getId(), stop.getId(), stop.getStopType(),
                    assignment == null ? null : assignment.getId(),
                    stop.getOrderId(), otpRequired, waitingForOtp, waitingForOtp, distance);
            return getSimulationStatus();
        }

        Coordinate next = moveToward(location.getLatitude(), location.getLongitude(),
                stop.getLatitude(), stop.getLongitude(), STEP_DISTANCE_METERS);
        locationRepository.upsertLocation(route.getCourierId(), next.lat(), next.lng(), OffsetDateTime.now(), true);

        simulationSession = session.withProgress("MOVING_TO_STOP",
                route.getId(), stop.getId(), stop.getStopType(),
                assignment == null ? null : assignment.getId(),
                stop.getOrderId(), otpRequired, waitingForOtp, waitingForOtp,
                Math.max(0, distance - STEP_DISTANCE_METERS));
        return getSimulationStatus();
    }

    public LogisticsDebugDto.SimulationStatusResponse stopSimulation() {
        simulationSession = SimulationSession.stopped();
        return getSimulationStatus();
    }

    public LogisticsDebugDto.SimulationStatusResponse getSimulationStatus() {
        return simulationSession.toDto();
    }

    @Transactional
    public void seedScenario(String scenarioName) {
        log.info("Debug scenario '{}' requested. Real scenario data is expected from scenario API scripts.", scenarioName);
    }

    @Transactional
    public void resetDebugData() {
        stopRepository.deleteAllInBatch();
        routeRepository.deleteAllInBatch();
        historyRepository.deleteAllInBatch();
        assignmentRepository.deleteAllInBatch();
        locationRepository.deleteAllInBatch();
        log.info("Resetting debug scenarios requested. Cleared all logistics data.");
    }

    @Transactional
    public void forceCourierLocation(UUID courierId, double lat, double lng, boolean isOnline) {
        locationRepository.upsertLocation(courierId, lat, lng, OffsetDateTime.now(), isOnline);
    }

    private LogisticsDebugDto.CourierMapDto toCourierDto(
            CourierLocation loc,
            Map<UUID, List<CourierAssignment>> activeAssignmentsByCourier,
            Map<UUID, CourierRoute> activeRouteByCourier) {

        String type = "UNKNOWN";
        UUID companyId = null;
        String employmentStatus = null;
        String transportType = null;
        String displayName = null;
        String identitySource = "SYNTHETIC_LABEL";

        try {
            Optional<CourierProfileClient.CourierProfileSnapshot> profileOpt = courierProfileClient.getCourier(loc.getCourierId());
            if (profileOpt.isPresent()) {
                var profile = profileOpt.get();
                type = profile.courierType();
                companyId = profile.companyId();
                employmentStatus = profile.employmentStatus();
                transportType = profile.transportType();
                displayName = profile.displayName();
                identitySource = displayName != null ? "REAL" : "PARTIAL";
            } else {
                return null; // Profile was deleted
            }
        } catch (Exception e) {
            log.warn("Failed to fetch profile for courier {}", loc.getCourierId());
        }

        if (displayName == null) {
            displayName = "Courier " + loc.getCourierId().toString().substring(0, 8);
        }

        List<CourierAssignment> activeAssignments = activeAssignmentsByCourier.getOrDefault(loc.getCourierId(), List.of());
        CourierRoute activeRoute = activeRouteByCourier.get(loc.getCourierId());
        RouteStop nextStop = activeRoute == null ? null : stopRepository
                .findFirstByRouteIdAndStatusOrderBySequenceNumberAsc(activeRoute.getId(), RouteStopStatus.PENDING)
                .orElse(null);
        long locationAgeSeconds = Math.max(0, ChronoUnit.SECONDS.between(loc.getUpdatedAt(), OffsetDateTime.now()));
        boolean busy = !activeAssignments.isEmpty() || activeRoute != null;
        String availability = !Boolean.TRUE.equals(loc.getIsOnline())
                ? "OFFLINE"
                : busy ? "BUSY" : "AVAILABLE";

        return LogisticsDebugDto.CourierMapDto.builder()
                .courierId(loc.getCourierId())
                .name(String.format("%s %s", type == null ? "COURIER" : type, loc.getCourierId().toString().substring(0, 4)))
                .displayName(displayName)
                .identitySource(identitySource)
                .courierType(type)
                .companyId(companyId)
                .lat(loc.getLatitude())
                .lng(loc.getLongitude())
                .online(loc.getIsOnline())
                .available(Boolean.TRUE.equals(loc.getIsOnline()) && !busy)
                .employmentStatus(employmentStatus)
                .currentLoad(activeRoute == null ? 0 : activeRoute.getCurrentLoadUnits())
                .maxCapacity(activeRoute == null ? 10 : activeRoute.getMaxCapacityUnits())
                .activeOrders(activeRoute == null ? activeAssignments.size() : activeRoute.getActiveOrdersCount())
                .transportType(transportType)
                .activeAssignmentIds(activeAssignments.stream().map(CourierAssignment::getId).toList())
                .assignedOrderIds(activeAssignments.stream().map(CourierAssignment::getOrderId).toList())
                .availabilityStatus(availability)
                .locationAgeSeconds(locationAgeSeconds)
                .currentRouteId(activeRoute == null ? null : activeRoute.getId())
                .nextStopId(nextStop == null ? null : nextStop.getId())
                .nextStopType(nextStop == null ? null : nextStop.getStopType())
                .distanceToNextStopMeters(nextStop == null ? null : distanceMeters(
                        loc.getLatitude(), loc.getLongitude(), nextStop.getLatitude(), nextStop.getLongitude()))
                .progressState(nextStop == null ? availability : "MOVING_TO_" + nextStop.getStopType())
                .build();
    }

    private LogisticsDebugDto.RouteMapDto toRouteDto(CourierRoute route, List<RouteStop> stops) {
        return LogisticsDebugDto.RouteMapDto.builder()
                .routeId(route.getId())
                .courierId(route.getCourierId())
                .status(route.getStatus())
                .currentLoad(route.getCurrentLoadUnits())
                .maxCapacity(route.getMaxCapacityUnits())
                .activeOrders(route.getActiveOrdersCount())
                .stops(stops.stream().map(this::toStopDto).toList())
                .build();
    }

    private LogisticsDebugDto.RouteStopMapDto toStopDto(RouteStop stop) {
        return LogisticsDebugDto.RouteStopMapDto.builder()
                .stopId(stop.getId())
                .sequence(stop.getSequenceNumber())
                .orderId(stop.getOrderId())
                .type(stop.getStopType())
                .status(stop.getStatus())
                .lat(stop.getLatitude())
                .lng(stop.getLongitude())
                .demandChange(stop.getStopType() == RouteStopType.PICKUP ? 1 : -1)
                .build();
    }

    private LogisticsDebugDto.AssignmentMapDto toAssignmentDto(CourierAssignment assignment) {
        return LogisticsDebugDto.AssignmentMapDto.builder()
                .assignmentId(assignment.getId())
                .orderId(assignment.getOrderId())
                .courierId(assignment.getCourierId())
                .status(assignment.getAssignmentStatus())
                .policy(assignment.getAssignmentPolicy())
                .score(assignment.getScore())
                .createdAt(assignment.getAssignedAt())
                .updatedAt(assignment.getUpdatedAt())
                .build();
    }

    private LogisticsDebugDto.OrderMapDto toOrderDto(OrderGrpcClient.OrderSnapshot order, CourierAssignment activeAssignment) {
        AssignmentStatus assignmentStatus = activeAssignment == null ? null : activeAssignment.getAssignmentStatus();
        boolean waitingForOtp = order.status() == OrderStatus.DELIVERY_CONFIRMATION_PENDING
                || assignmentStatus == AssignmentStatus.ARRIVED;
        boolean assigned = activeAssignment != null && activeAssignment.getCourierId() != null;

        return LogisticsDebugDto.OrderMapDto.builder()
                .orderId(order.orderId())
                .status(order.status())
                .serviceType(order.serviceType())
                .companyId(order.companyId())
                .parcelSize(order.parcelSize())
                .pickupLat(order.pickupLatitude())
                .pickupLng(order.pickupLongitude())
                .deliveryLat(order.deliveryLatitude())
                .deliveryLng(order.deliveryLongitude())
                .assignedCourierId(activeAssignment == null ? null : activeAssignment.getCourierId())
                .assignmentState(activeAssignment == null ? "UNASSIGNED" : assignmentStatus.name())
                .activeAssignmentId(activeAssignment == null ? null : activeAssignment.getId())
                .assignmentPolicy(activeAssignment == null ? null : activeAssignment.getAssignmentPolicy())
                .assignmentStatus(assignmentStatus)
                .unassignedReason(assigned ? null : unassignedReason(order.status()))
                .otpRequired(order.status() == OrderStatus.DELIVERY_CONFIRMATION_PENDING || assignmentStatus == AssignmentStatus.ARRIVED)
                .waitingForOtp(waitingForOtp)
                .canConfirmDelivery(waitingForOtp)
                .progressState(progressState(order.status(), assignmentStatus))
                .build();
    }

    private List<OrderGrpcClient.OrderSnapshot> loadVisibleOrders(
            UUID selectedOrderId,
            List<CourierAssignment> assignments,
            List<RouteStop> stops,
            ScopeBounds bounds,
            int maxOrders) {

        Map<UUID, OrderGrpcClient.OrderSnapshot> orders = new LinkedHashMap<>();
        Set<UUID> ids = new LinkedHashSet<>();
        if (selectedOrderId != null) {
            ids.add(selectedOrderId);
        }
        assignments.forEach(a -> ids.add(a.getOrderId()));
        stops.forEach(s -> ids.add(s.getOrderId()));

        for (UUID id : ids) {
            try {
                OrderGrpcClient.OrderSnapshot snapshot = orderGrpcClient.getOrder(id);
                if (isOrderInBounds(snapshot, bounds)) {
                    orders.put(snapshot.orderId(), snapshot);
                }
            } catch (Exception e) {
                log.warn("Failed to fetch order {} from order-service for debug map: {}", id, e.getMessage());
            }
        }

        try {
            orderGrpcClient.listOrdersByStatuses(OPERATIONS_ORDER_STATUSES, Math.max(10, maxOrders / OPERATIONS_ORDER_STATUSES.size()))
                    .stream()
                    .filter(order -> isOrderInBounds(order, bounds))
                    .limit(Math.max(1, maxOrders))
                    .forEach(order -> orders.putIfAbsent(order.orderId(), order));
        } catch (Exception e) {
            log.warn("Failed to list operational orders from order-service: {}", e.getMessage());
        }

        return orders.values().stream().limit(Math.max(1, maxOrders)).toList();
    }

    private LogisticsDebugDto.AssignmentMapSummaryDto buildSummary(
            List<LogisticsDebugDto.CourierMapDto> couriers,
            List<LogisticsDebugDto.OrderMapDto> orders,
            List<CourierAssignment> assignments,
            List<CourierRoute> routes) {

        return LogisticsDebugDto.AssignmentMapSummaryDto.builder()
                .totalCouriers(couriers.size())
                .onlineCouriers((int) couriers.stream().filter(c -> Boolean.TRUE.equals(c.online())).count())
                .availableCouriers((int) couriers.stream().filter(c -> Boolean.TRUE.equals(c.available())).count())
                .busyCouriers((int) couriers.stream().filter(c -> "BUSY".equals(c.availabilityStatus())).count())
                .totalOrders(orders.size())
                .unassignedReadyOrders((int) orders.stream().filter(o -> o.activeAssignmentId() == null && o.status() == OrderStatus.READY).count())
                .assignmentPendingOrders((int) orders.stream().filter(o -> o.status() == OrderStatus.ASSIGNMENT_PENDING).count())
                .assignedOrders((int) orders.stream().filter(o -> o.assignedCourierId() != null).count())
                .pendingOffers((int) assignments.stream().filter(a -> a.getAssignmentStatus() == AssignmentStatus.PENDING).count())
                .manualRequiredAssignments((int) assignments.stream().filter(a -> a.getAssignmentStatus() == AssignmentStatus.MANUAL_REQUIRED).count())
                .timedOutAssignments((int) assignments.stream().filter(a -> a.getAssignmentStatus() == AssignmentStatus.TIMED_OUT).count())
                .rejectedAssignments((int) assignments.stream().filter(a -> a.getAssignmentStatus() == AssignmentStatus.REJECTED).count())
                .activeRoutes((int) routes.stream().filter(r -> r.getStatus() == RouteStatus.ACTIVE).count())
                .completedRoutes((int) routes.stream().filter(r -> r.getStatus() == RouteStatus.COMPLETED).count())
                .build();
    }

    private boolean shouldShowAssignment(CourierAssignment assignment, Set<UUID> inScopeCourierIds, UUID orderId) {
        return (assignment.getCourierId() != null && inScopeCourierIds.contains(assignment.getCourierId()))
                || (orderId != null && orderId.equals(assignment.getOrderId()))
                || assignment.getAssignmentStatus() == AssignmentStatus.MANUAL_REQUIRED
                || assignment.getAssignmentStatus() == AssignmentStatus.PENDING;
    }

    private Optional<CourierRoute> selectSimulationRoute(SimulationSession session) {
        for (UUID routeId : session.routeIds()) {
            Optional<CourierRoute> route = routeRepository.findById(routeId)
                    .filter(r -> r.getStatus() == RouteStatus.ACTIVE);
            if (route.isPresent()) {
                return route;
            }
        }
        for (UUID courierId : session.courierIds()) {
            Optional<CourierRoute> route = routeRepository.findByCourierIdAndStatus(courierId, RouteStatus.ACTIVE);
            if (route.isPresent()) {
                return route;
            }
        }
        for (UUID assignmentId : session.assignmentIds()) {
            Optional<CourierRoute> route = assignmentRepository.findById(assignmentId)
                    .map(CourierAssignment::getRouteId)
                    .flatMap(routeRepository::findById)
                    .filter(r -> r.getStatus() == RouteStatus.ACTIVE);
            if (route.isPresent()) {
                return route;
            }
        }
        if (session.allowAllActiveRoutes()) {
            return routeRepository.findAll().stream()
                    .filter(route -> route.getStatus() == RouteStatus.ACTIVE)
                    .findFirst();
        }
        return Optional.empty();
    }

    private Optional<CourierAssignment> findActiveAssignmentForStop(CourierRoute route, RouteStop stop) {
        return assignmentRepository.findAll().stream()
                .filter(a -> stop.getOrderId().equals(a.getOrderId()))
                .filter(a -> route.getCourierId().equals(a.getCourierId()))
                .filter(a -> ACTIVE_ASSIGNMENT_STATUSES.contains(a.getAssignmentStatus()))
                .findFirst();
    }

    private AssignmentStatus nextLifecycleStatus(CourierAssignment assignment, RouteStop stop) {
        if (assignment == null) {
            return null;
        }
        if (stop.getStopType() == RouteStopType.PICKUP) {
            return switch (assignment.getAssignmentStatus()) {
                case ASSIGNED, PENDING -> AssignmentStatus.ACCEPTED;
                case ACCEPTED -> AssignmentStatus.PICKED_UP;
                case PICKED_UP -> AssignmentStatus.IN_TRANSIT;
                default -> null;
            };
        }
        return switch (assignment.getAssignmentStatus()) {
            case PICKED_UP -> AssignmentStatus.IN_TRANSIT;
            case IN_TRANSIT -> AssignmentStatus.ARRIVED;
            case ARRIVED -> null;
            default -> null;
        };
    }

    private ScopeBounds resolveBounds(
            String cityScope,
            Double customMinLat,
            Double customMinLng,
            Double customMaxLat,
            Double customMaxLng) {
        String normalized = cityScope == null || cityScope.isBlank()
                ? "ASTANA"
                : cityScope.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ALMATY" -> new ScopeBounds("ALMATY", 43.05, 76.70, 43.40, 77.10, 43.2389, 76.8897, DEFAULT_ZOOM);
            case "CUSTOM" -> {
                if (customMinLat == null || customMinLng == null || customMaxLat == null || customMaxLng == null) {
                    throw new BusinessException("CUSTOM_SCOPE_BOUNDS_REQUIRED",
                            "CUSTOM city scope requires customMinLat, customMinLng, customMaxLat, customMaxLng");
                }
                yield new ScopeBounds("CUSTOM", customMinLat, customMinLng, customMaxLat, customMaxLng,
                        (customMinLat + customMaxLat) / 2.0, (customMinLng + customMaxLng) / 2.0, DEFAULT_ZOOM);
            }
            case "ASTANA" -> new ScopeBounds("ASTANA", 51.00, 71.20, 51.32, 71.70, 51.1694, 71.4491, DEFAULT_ZOOM);
            default -> throw new BusinessException("INVALID_CITY_SCOPE",
                    "cityScope must be ASTANA, ALMATY, or CUSTOM");
        };
    }

    private boolean isOrderInBounds(OrderGrpcClient.OrderSnapshot order, ScopeBounds bounds) {
        return bounds.contains(order.pickupLatitude(), order.pickupLongitude())
                || bounds.contains(order.deliveryLatitude(), order.deliveryLongitude());
    }

    private String unassignedReason(OrderStatus status) {
        return switch (status) {
            case READY -> "READY_NO_ACTIVE_ASSIGNMENT";
            case ASSIGNMENT_PENDING -> "WAITING_FOR_ASSIGNMENT_RETRY";
            default -> "NO_ACTIVE_ASSIGNMENT";
        };
    }

    private String progressState(OrderStatus orderStatus, AssignmentStatus assignmentStatus) {
        if (assignmentStatus != null) {
            return assignmentStatus.name();
        }
        return orderStatus == null ? "UNKNOWN" : orderStatus.name();
    }

    private List<UUID> cleanIds(List<UUID> ids) {
        return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).toList();
    }

    private List<UUID> distinct(List<UUID> ids) {
        return ids.stream().distinct().toList();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double earthRadiusMeters = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusMeters * c;
    }

    private Coordinate moveToward(double lat, double lng, double targetLat, double targetLng, double meters) {
        double distance = distanceMeters(lat, lng, targetLat, targetLng);
        if (distance <= meters || distance == 0) {
            return new Coordinate(targetLat, targetLng);
        }
        double ratio = meters / distance;
        return new Coordinate(lat + (targetLat - lat) * ratio, lng + (targetLng - lng) * ratio);
    }

    private record Coordinate(double lat, double lng) {}

    private record ScopeBounds(
            String cityScope,
            double minLat,
            double minLng,
            double maxLat,
            double maxLng,
            double centerLat,
            double centerLng,
            int zoom) {

        boolean contains(double lat, double lng) {
            return lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng;
        }

        LogisticsDebugDto.ScopeInfoDto toDto() {
            return LogisticsDebugDto.ScopeInfoDto.builder()
                    .cityScope(cityScope)
                    .minLat(minLat)
                    .minLng(minLng)
                    .maxLat(maxLat)
                    .maxLng(maxLng)
                    .centerLat(centerLat)
                    .centerLng(centerLng)
                    .zoom(zoom)
                    .build();
        }
    }

    private record SimulationSession(
            boolean running,
            String progressState,
            String blockedReason,
            OffsetDateTime startedAt,
            OffsetDateTime lastStepAt,
            List<UUID> courierIds,
            List<UUID> assignmentIds,
            List<UUID> routeIds,
            String scenarioName,
            boolean allowAllActiveRoutes,
            UUID currentRouteId,
            UUID currentStopId,
            RouteStopType currentStopType,
            UUID currentAssignmentId,
            UUID currentOrderId,
            boolean otpRequired,
            boolean waitingForOtp,
            boolean canConfirmDelivery,
            Double distanceToNextStopMeters) {

        static SimulationSession stopped() {
            return new SimulationSession(false, "STOPPED", null, null, null,
                    List.of(), List.of(), List.of(), null, false,
                    null, null, null, null, null, false, false, false, null);
        }

        SimulationSession withProgress(
                String progressState,
                UUID routeId,
                UUID stopId,
                RouteStopType stopType,
                UUID assignmentId,
                UUID orderId,
                boolean otpRequired,
                boolean waitingForOtp,
                boolean canConfirmDelivery,
                Double distanceToNextStopMeters) {
            return new SimulationSession(true, progressState, null, startedAt, OffsetDateTime.now(),
                    courierIds, assignmentIds, routeIds, scenarioName, allowAllActiveRoutes,
                    routeId, stopId, stopType, assignmentId, orderId,
                    otpRequired, waitingForOtp, canConfirmDelivery, distanceToNextStopMeters);
        }

        SimulationSession withBlocked(
                String blockedReason,
                UUID routeId,
                UUID stopId,
                RouteStopType stopType,
                UUID assignmentId,
                UUID orderId,
                boolean otpRequired,
                boolean waitingForOtp,
                boolean canConfirmDelivery,
                Double distanceToNextStopMeters) {
            return new SimulationSession(false, "BLOCKED", blockedReason, startedAt, OffsetDateTime.now(),
                    courierIds, assignmentIds, routeIds, scenarioName, allowAllActiveRoutes,
                    routeId, stopId, stopType, assignmentId, orderId,
                    otpRequired, waitingForOtp, canConfirmDelivery, distanceToNextStopMeters);
        }

        LogisticsDebugDto.SimulationStatusResponse toDto() {
            return LogisticsDebugDto.SimulationStatusResponse.builder()
                    .running(running)
                    .progressState(progressState)
                    .blockedReason(blockedReason)
                    .startedAt(startedAt)
                    .lastStepAt(lastStepAt)
                    .courierIds(courierIds)
                    .assignmentIds(assignmentIds)
                    .routeIds(routeIds)
                    .scenarioName(scenarioName)
                    .allowAllActiveRoutes(allowAllActiveRoutes)
                    .currentRouteId(currentRouteId)
                    .currentStopId(currentStopId)
                    .currentStopType(currentStopType)
                    .currentAssignmentId(currentAssignmentId)
                    .currentOrderId(currentOrderId)
                    .otpRequired(otpRequired)
                    .waitingForOtp(waitingForOtp)
                    .canConfirmDelivery(canConfirmDelivery)
                    .distanceToNextStopMeters(distanceToNextStopMeters)
                    .build();
        }
    }
}
