package kz.courier.logisticsservice.service;

import kz.courier.logisticsservice.dto.LogisticsDto;
import kz.courier.logisticsservice.entity.*;
import kz.courier.logisticsservice.exception.BusinessException;
import kz.courier.logisticsservice.grpc.OrderGrpcClient;
import kz.courier.logisticsservice.kafka.AssignmentEventPublisher;
import kz.courier.logisticsservice.mapper.AssignmentMapper;
import kz.courier.logisticsservice.repository.*;
import kz.courier.logisticsservice.security.GatewayPrincipalProvider;
import kz.courier.order.v1.OrderStatus;
import kz.courier.order.v1.ParcelSize;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CapacityAwareAssignmentService {

    private static final List<AssignmentStatus> ACTIVE_ASSIGNMENT_EXCLUSIONS =
            List.of(AssignmentStatus.DELIVERED, AssignmentStatus.CANCELLED, AssignmentStatus.FAILED,
                    AssignmentStatus.REJECTED, AssignmentStatus.MANUAL_REQUIRED);
    private static final Set<OrderStatus> AUTO_ASSIGNABLE_ORDER_STATUSES =
            EnumSet.of(OrderStatus.NEW, OrderStatus.ACCEPTED, OrderStatus.PREPARING,
                    OrderStatus.READY, OrderStatus.ASSIGNED);
    private static final double AUTO_ASSIGN_RADIUS_METERS = 5_000.0;
    private static final int AUTO_ASSIGN_LIMIT = 20;
    private static final long MAX_LOCATION_AGE_MINUTES = 10;
    private static final double AVERAGE_COURIER_SPEED_METERS_PER_MINUTE = 250.0;
    private static final List<AssignmentStatus> TERMINAL_ASSIGNMENT_STATUSES =
            List.of(AssignmentStatus.DELIVERED, AssignmentStatus.CANCELLED, AssignmentStatus.FAILED,
                    AssignmentStatus.REJECTED, AssignmentStatus.MANUAL_REQUIRED);

    private final AssignmentRepository assignmentRepository;
    private final AssignmentHistoryRepository historyRepository;
    private final CourierLocationRepository locationRepository;
    private final CourierRouteRepository routeRepository;
    private final RouteStopRepository stopRepository;
    private final AssignmentMapper mapper;
    private final AssignmentEventPublisher eventPublisher;
    private final OrderGrpcClient orderGrpcClient;
    private final GatewayPrincipalProvider gatewayPrincipalProvider;
    private final CourierProfileClient courierProfileClient;

    @Value("${assignment.retry.delay-seconds:60}")
    private long retryDelaySeconds;

    @Transactional
    public LogisticsDto.AutoAssignResponse autoAssign(UUID orderId) {
        UUID actorId = gatewayPrincipalProvider.requireCurrentUserId();
        if (!assignmentRepository.lockActiveAssignmentsByOrderId(orderId).isEmpty()) {
            throw new BusinessException("DUPLICATE_ASSIGNMENT",
                    "An active assignment already exists for order " + orderId);
        }

        OrderGrpcClient.OrderSnapshot order;
        try {
            order = orderGrpcClient.getOrder(orderId);
        } catch (BusinessException ex) {
            AssignmentFailureReason reason = classifyOrderFailure(ex);
            CourierAssignment failed = persistManualRequired(orderId, null, 0, 0,
                    reason, ex.getMessage(), actorId);
            return failureResponse(orderId, failed, 0, 0, reason, ex.getMessage());
        }

        if (!AUTO_ASSIGNABLE_ORDER_STATUSES.contains(order.status())) {
            String message = "Order " + order.orderId() + " with status " + order.status()
                    + " cannot be auto-assigned";
            CourierAssignment failed = persistManualRequired(orderId, demandUnits(order), 0, 0,
                    AssignmentFailureReason.INVALID_ORDER_DATA, message, actorId);
            return failureResponse(orderId, failed, 0, 0,
                    AssignmentFailureReason.INVALID_ORDER_DATA, message);
        }

        int demandUnits = demandUnits(order);
        List<LogisticsDto.NearbyCourierResponse> nearbyCouriers = findNearby(order);
        AssignmentFailureReason noPlanReason = nearbyCouriers.isEmpty()
                ? AssignmentFailureReason.NO_ONLINE_COURIERS
                : AssignmentFailureReason.NO_CAPACITY_AVAILABLE;
        List<CandidatePlan> rankedPlans;
        try {
            rankedPlans = nearbyCouriers.stream()
                    .map(candidate -> evaluateCandidate(candidate, order, demandUnits))
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparingDouble(CandidatePlan::score))
                    .toList();
        } catch (CourierProfileClient.CourierProfileUnavailableException ex) {
            String message = "Courier profile service is unavailable";
            CourierAssignment failed = persistManualRequired(orderId, demandUnits, nearbyCouriers.size(), 0,
                    AssignmentFailureReason.COURIER_SERVICE_UNAVAILABLE, message, actorId);
            return failureResponse(orderId, failed, nearbyCouriers.size(), 0,
                    AssignmentFailureReason.COURIER_SERVICE_UNAVAILABLE, message);
        }

        for (CandidatePlan plan : rankedPlans) {
            Optional<CourierAssignment> assignment = tryCommitPlan(plan, order, demandUnits, actorId,
                    "capacity-aware-auto-assign");
            if (assignment.isPresent()) {
                CourierAssignment saved = assignment.get();
                resolveManualRequired(orderId, saved.getId());
                return LogisticsDto.AutoAssignResponse.builder()
                        .assigned(mapper.toResponse(saved))
                        .orderId(orderId)
                        .courierId(saved.getCourierId())
                        .routeId(saved.getRouteId())
                        .assignmentStatus(saved.getAssignmentStatus())
                        .score(saved.getScore())
                        .message("Courier assigned using capacity-aware insertion heuristic")
                        .selectedCourier(LogisticsDto.AutoAssignedCourier.builder()
                                .courierId(plan.courierId())
                                .distanceMeters(round(plan.distanceToPickupMeters()))
                                .etaMinutes(estimateEtaMinutes(plan.distanceToPickupMeters()))
                                .distanceScore(round(plan.addedRouteDistanceMeters()))
                                .freshnessScore(round(plan.capacityUsagePenalty()))
                                .totalScore(round(plan.score()))
                                .locationUpdatedAt(plan.locationUpdatedAt())
                                .build())
                        .scannedCouriers(nearbyCouriers.size())
                        .eligibleCouriers(rankedPlans.size())
                        .searchRadiusMeters(AUTO_ASSIGN_RADIUS_METERS)
                        .evaluatedAt(OffsetDateTime.now())
                        .build();
            }
        }

        String message = nearbyCouriers.isEmpty()
                ? "No online couriers were found near the pickup point"
                : "No courier had enough capacity or a feasible route insertion";
        CourierAssignment failed = persistManualRequired(orderId, demandUnits, nearbyCouriers.size(),
                rankedPlans.size(), noPlanReason, message, actorId);
        return failureResponse(orderId, failed, nearbyCouriers.size(), rankedPlans.size(),
                noPlanReason, message);
    }

    @Transactional
    public LogisticsDto.AssignmentResponse manualAssign(LogisticsDto.ManualAssignmentRequest request) {
        UUID actorId = gatewayPrincipalProvider.requireCurrentUserId();
        UUID orderId = request.orderId();
        UUID courierId = request.courierId();

        if (!assignmentRepository.lockActiveAssignmentsByOrderId(orderId).isEmpty()) {
            throw new BusinessException("DUPLICATE_ASSIGNMENT",
                    "An active assignment already exists for order " + orderId);
        }

        OrderGrpcClient.OrderSnapshot order = orderGrpcClient.getOrder(orderId);
        if (!AUTO_ASSIGNABLE_ORDER_STATUSES.contains(order.status())) {
            throw new BusinessException("ORDER_NOT_ASSIGNABLE",
                    "Order " + order.orderId() + " with status " + order.status() + " cannot be assigned");
        }

        int demandUnits = demandUnits(order);
        CourierLocation location = locationRepository.findById(courierId)
                .orElseThrow(() -> new BusinessException("COURIER_LOCATION_MISSING",
                        "Courier " + courierId + " has no known location"));

        LogisticsDto.NearbyCourierResponse candidate = LogisticsDto.NearbyCourierResponse.builder()
                .courierId(courierId)
                .latitude(location.getLatitude())
                .longitude(location.getLongitude())
                .isOnline(location.getIsOnline())
                .updatedAt(location.getUpdatedAt())
                .build();

        CandidatePlan plan;
        try {
            plan = evaluateCandidate(candidate, order, demandUnits)
                    .orElseThrow(() -> new BusinessException("COURIER_NOT_FEASIBLE",
                            "Selected courier is not eligible, lacks capacity, or has no feasible insertion"));
        } catch (CourierProfileClient.CourierProfileUnavailableException ex) {
            throw new BusinessException("COURIER_SERVICE_UNAVAILABLE",
                    "Courier profile service is unavailable");
        }

        CourierAssignment saved = tryCommitPlan(plan, order, demandUnits, actorId,
                request.reason() == null || request.reason().isBlank()
                        ? "manual-assignment"
                        : request.reason())
                .orElseThrow(() -> new BusinessException("COURIER_CAPACITY_CHANGED",
                        "Selected courier became infeasible during assignment"));
        resolveManualRequired(orderId, saved.getId());
        return mapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public boolean hasActiveAssignment(UUID orderId) {
        return assignmentRepository.existsByOrderIdAndAssignmentStatusNotIn(
                orderId, TERMINAL_ASSIGNMENT_STATUSES);
    }

    private List<LogisticsDto.NearbyCourierResponse> findNearby(OrderGrpcClient.OrderSnapshot order) {
        return locationRepository.findNearbyCouriers(
                        order.pickupLatitude(),
                        order.pickupLongitude(),
                        AUTO_ASSIGN_RADIUS_METERS,
                        AUTO_ASSIGN_LIMIT)
                .stream()
                .map(p -> LogisticsDto.NearbyCourierResponse.builder()
                        .courierId(p.getCourierId())
                        .latitude(p.getLatitude())
                        .longitude(p.getLongitude())
                        .distanceMeters(p.getDistanceMeters())
                        .isOnline(p.getIsOnline())
                        .updatedAt(p.getUpdatedAt() == null ? null : p.getUpdatedAt().atOffset(java.time.ZoneOffset.UTC))
                        .build())
                .toList();
    }

    private Optional<CandidatePlan> evaluateCandidate(
            LogisticsDto.NearbyCourierResponse candidate,
            OrderGrpcClient.OrderSnapshot order,
            int demandUnits) {

        if (!Boolean.TRUE.equals(candidate.isOnline()) || candidate.updatedAt() == null) {
            return Optional.empty();
        }
        long ageMinutes = ChronoUnit.MINUTES.between(candidate.updatedAt(), OffsetDateTime.now());
        if (ageMinutes > MAX_LOCATION_AGE_MINUTES) {
            return Optional.empty();
        }

        Optional<CourierProfileClient.CourierProfileSnapshot> profileOpt =
                courierProfileClient.getCourier(candidate.courierId());
        if (profileOpt.isEmpty() || !isEligible(profileOpt.get())) {
            return Optional.empty();
        }

        CourierProfileClient.CourierProfileSnapshot profile = profileOpt.get();
        int maxCapacity = vehicleCapacity(profile.transportType());
        if (demandUnits > maxCapacity) {
            return Optional.empty();
        }

        Optional<CourierRoute> routeOpt =
                routeRepository.findByCourierIdAndStatus(candidate.courierId(), RouteStatus.ACTIVE);
        List<RouteStop> existingStops = routeOpt
                .map(route -> stopRepository.findAllByRouteIdOrderBySequenceNumberAsc(route.getId()))
                .orElseGet(List::of);

        int currentLoad = routeOpt.map(CourierRoute::getCurrentLoadUnits).orElse(0);
        int activeOrders = routeOpt.map(CourierRoute::getActiveOrdersCount).orElse(0);
        int maxActiveOrders = Math.max(1, profile.maxActiveOrders());
        if (currentLoad + demandUnits > maxCapacity || activeOrders >= maxActiveOrders) {
            return Optional.empty();
        }

        Coordinate courierLocation = new Coordinate(candidate.latitude(), candidate.longitude());
        Coordinate pickup = new Coordinate(order.pickupLatitude(), order.pickupLongitude());
        Coordinate dropoff = new Coordinate(order.deliveryLatitude(), order.deliveryLongitude());
        Optional<InsertionPlan> insertion = bestInsertion(routeOpt.orElse(null), existingStops,
                courierLocation, order.orderId(), pickup, dropoff);
        if (insertion.isEmpty()) {
            return Optional.empty();
        }

        double addedRouteDistance = insertion.get().addedRouteDistanceMeters();
        double distanceToPickup = haversineMeters(courierLocation, pickup);
        double activeOrdersPenalty = ((double) activeOrders / maxActiveOrders) * AUTO_ASSIGN_RADIUS_METERS;
        double capacityUsagePenalty = ((double) (currentLoad + demandUnits) / maxCapacity) * AUTO_ASSIGN_RADIUS_METERS;
        double score = round(addedRouteDistance * 0.45
                + activeOrdersPenalty * 0.20
                + capacityUsagePenalty * 0.20
                + distanceToPickup * 0.15);

        AssignmentPolicy policy = "EMPLOYEE".equals(profile.courierType())
                ? AssignmentPolicy.DIRECT
                : AssignmentPolicy.OFFER;
        AssignmentStatus status = policy == AssignmentPolicy.DIRECT
                ? AssignmentStatus.ASSIGNED
                : AssignmentStatus.PENDING;

        return Optional.of(new CandidatePlan(
                candidate.courierId(),
                maxCapacity,
                currentLoad,
                activeOrders,
                maxActiveOrders,
                policy,
                status,
                insertion.get(),
                score,
                addedRouteDistance,
                distanceToPickup,
                capacityUsagePenalty,
                candidate.updatedAt()));
    }

    private Optional<CourierAssignment> tryCommitPlan(
            CandidatePlan plan,
            OrderGrpcClient.OrderSnapshot order,
            int demandUnits,
            UUID actorId,
            String source) {

        locationRepository.lockByCourierId(plan.courierId()).orElse(null);
        if (!assignmentRepository.lockActiveAssignmentsByOrderId(order.orderId()).isEmpty()) {
            throw new BusinessException("DUPLICATE_ASSIGNMENT",
                    "An active assignment already exists for order " + order.orderId());
        }

        Optional<CourierRoute> lockedRouteOpt = routeRepository.lockActiveRouteByCourierId(plan.courierId());
        CourierRoute route = lockedRouteOpt.orElse(null);
        List<RouteStop> lockedStops = route == null
                ? List.of()
                : stopRepository.findAllByRouteIdOrderBySequenceNumberAsc(route.getId());

        int currentLoad = route == null ? 0 : route.getCurrentLoadUnits();
        int activeOrders = route == null ? 0 : route.getActiveOrdersCount();
        if (currentLoad + demandUnits > plan.maxCapacityUnits()
                || activeOrders >= plan.maxActiveOrders()) {
            log.debug("Candidate lost capacity race courierId={}", plan.courierId());
            return Optional.empty();
        }

        Coordinate courierLocation = plan.insertionPlan().courierLocation();
        Coordinate pickup = new Coordinate(order.pickupLatitude(), order.pickupLongitude());
        Coordinate dropoff = new Coordinate(order.deliveryLatitude(), order.deliveryLongitude());
        Optional<InsertionPlan> insertion = bestInsertion(route, lockedStops, courierLocation,
                order.orderId(), pickup, dropoff);
        if (insertion.isEmpty()) {
            return Optional.empty();
        }

        if (route == null) {
            route = CourierRoute.builder()
                    .courierId(plan.courierId())
                    .status(RouteStatus.ACTIVE)
                    .currentLoadUnits(0)
                    .maxCapacityUnits(plan.maxCapacityUnits())
                    .activeOrdersCount(0)
                    .build();
            route = routeRepository.saveAndFlush(route);
        }

        route.setCurrentLoadUnits(currentLoad + demandUnits);
        route.setMaxCapacityUnits(plan.maxCapacityUnits());
        route.setActiveOrdersCount(activeOrders + 1);
        route = routeRepository.save(route);

        persistStops(route.getId(), insertion.get());

        CourierAssignment assignment = CourierAssignment.builder()
                .orderId(order.orderId())
                .courierId(plan.courierId())
                .routeId(route.getId())
                .assignedBy(actorId)
                .assignmentStatus(plan.assignmentStatus())
                .assignmentPolicy(plan.assignmentPolicy())
                .assignedAt(OffsetDateTime.now())
                .etaMinutes(estimateEtaMinutes(plan.distanceToPickupMeters()))
                .score(plan.score())
                .demandUnits(demandUnits)
                .build();
        assignment = assignmentRepository.saveAndFlush(assignment);

        historyRepository.save(AssignmentHistory.builder()
                .assignmentId(assignment.getId())
                .oldStatus(null)
                .newStatus(plan.assignmentStatus())
                .changedBy(actorId)
                .reason(source)
                .changedAt(OffsetDateTime.now())
                .build());
        eventPublisher.publishAssignmentCreated(
                assignment.getId(), assignment.getOrderId(), assignment.getCourierId(),
                assignment.getAssignmentStatus());

        return Optional.of(assignment);
    }

    private CourierAssignment persistManualRequired(UUID orderId,
                                                    Integer demandUnits,
                                                    int scannedCandidates,
                                                    int eligibleCandidates,
                                                    AssignmentFailureReason reason,
                                                    String message,
                                                    UUID actorId) {
        CourierAssignment assignment = assignmentRepository.lockUnresolvedManualRequiredByOrderId(orderId)
                .orElseGet(() -> CourierAssignment.builder()
                        .orderId(orderId)
                        .assignmentStatus(AssignmentStatus.MANUAL_REQUIRED)
                        .assignedAt(OffsetDateTime.now())
                        .assignedBy(actorId)
                        .retryCount(0)
                        .build());

        assignment.setAssignmentStatus(AssignmentStatus.MANUAL_REQUIRED);
        assignment.setCourierId(null);
        assignment.setRouteId(null);
        assignment.setScore(null);
        assignment.setDemandUnits(demandUnits);
        assignment.setFailureReason(reason);
        assignment.setFailureMessage(message);
        assignment.setScannedCandidates(scannedCandidates);
        assignment.setEligibleCandidates(eligibleCandidates);
        assignment.setNextRetryAt(reason.isTemporary()
                ? OffsetDateTime.now().plusSeconds(retryDelaySeconds)
                : null);

        CourierAssignment saved = assignmentRepository.saveAndFlush(assignment);
        eventPublisher.publishStatusChanged(saved.getId(), orderId, null, null, AssignmentStatus.MANUAL_REQUIRED);
        return saved;
    }

    private void resolveManualRequired(UUID orderId, UUID resolvedAssignmentId) {
        assignmentRepository.lockUnresolvedManualRequiredByOrderId(orderId)
                .ifPresent(failed -> {
                    failed.setResolvedAt(OffsetDateTime.now());
                    failed.setResolvedAssignmentId(resolvedAssignmentId);
                    assignmentRepository.save(failed);
                });
    }

    private LogisticsDto.AutoAssignResponse failureResponse(UUID orderId,
                                                            CourierAssignment failed,
                                                            int scanned,
                                                            int eligible,
                                                            AssignmentFailureReason reason,
                                                            String message) {
        return LogisticsDto.AutoAssignResponse.builder()
                .assigned(mapper.toResponse(failed))
                .orderId(orderId)
                .assignmentStatus(AssignmentStatus.MANUAL_REQUIRED)
                .failureReason(reason)
                .failureMessage(message)
                .message(message)
                .scannedCouriers(scanned)
                .eligibleCouriers(eligible)
                .searchRadiusMeters(AUTO_ASSIGN_RADIUS_METERS)
                .evaluatedAt(OffsetDateTime.now())
                .build();
    }

    private AssignmentFailureReason classifyOrderFailure(BusinessException ex) {
        return switch (ex.getCode()) {
            case "ORDER_PICKUP_LOCATION_MISSING", "ORDER_DELIVERY_LOCATION_MISSING" ->
                    AssignmentFailureReason.MISSING_ORDER_COORDINATES;
            case "ORDER_NOT_FOUND", "ORDER_NOT_ASSIGNABLE", "INVALID_ARGUMENT" ->
                    AssignmentFailureReason.INVALID_ORDER_DATA;
            default -> AssignmentFailureReason.UNKNOWN;
        };
    }

    private Optional<InsertionPlan> bestInsertion(CourierRoute route,
                                                  List<RouteStop> existingStops,
                                                  Coordinate courierLocation,
                                                  UUID orderId,
                                                  Coordinate pickup,
                                                  Coordinate dropoff) {
        List<StopPlan> base = existingStops.stream()
                .filter(stop -> stop.getStatus() != RouteStopStatus.CANCELLED)
                .map(StopPlan::existing)
                .toList();
        int mutableStart = 0;
        for (int i = 0; i < base.size(); i++) {
            if (base.get(i).status() == RouteStopStatus.COMPLETED) {
                mutableStart = i + 1;
            }
        }

        double existingDistance = route == null
                ? 0.0
                : routeDistance(courierLocation, base.subList(mutableStart, base.size()));

        StopPlan pickupStop = StopPlan.newStop(orderId, RouteStopType.PICKUP, pickup);
        StopPlan dropoffStop = StopPlan.newStop(orderId, RouteStopType.DROPOFF, dropoff);
        InsertionPlan best = null;

        for (int pickupIndex = mutableStart; pickupIndex <= base.size(); pickupIndex++) {
            for (int dropoffIndex = pickupIndex + 1; dropoffIndex <= base.size() + 1; dropoffIndex++) {
                List<StopPlan> planned = new ArrayList<>(base);
                planned.add(pickupIndex, pickupStop);
                planned.add(dropoffIndex, dropoffStop);
                if (!pickupBeforeDropoff(planned, orderId)) {
                    continue;
                }
                double newDistance = routeDistance(courierLocation,
                        planned.subList(mutableStart, planned.size()));
                double added = Math.max(0.0, newDistance - existingDistance);
                if (best == null || added < best.addedRouteDistanceMeters()) {
                    best = new InsertionPlan(courierLocation, planned, added);
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private void persistStops(UUID routeId, InsertionPlan insertion) {
        List<RouteStop> existingMutable = insertion.stops().stream()
                .map(StopPlan::existingStop)
                .filter(Objects::nonNull)
                .filter(stop -> stop.getStatus() != RouteStopStatus.COMPLETED)
                .toList();
        for (int i = 0; i < existingMutable.size(); i++) {
            existingMutable.get(i).setSequenceNumber(10_000 + i);
        }
        stopRepository.saveAll(existingMutable);
        stopRepository.flush();

        List<RouteStop> toSave = new ArrayList<>();
        for (int i = 0; i < insertion.stops().size(); i++) {
            StopPlan plan = insertion.stops().get(i);
            RouteStop stop = plan.existingStop();
            if (stop == null) {
                stop = RouteStop.builder()
                        .routeId(routeId)
                        .orderId(plan.orderId())
                        .stopType(plan.stopType())
                        .latitude(plan.coordinate().latitude())
                        .longitude(plan.coordinate().longitude())
                        .status(RouteStopStatus.PENDING)
                        .build();
            } else if (stop.getStatus() == RouteStopStatus.COMPLETED) {
                continue;
            }
            stop.setSequenceNumber(i + 1);
            toSave.add(stop);
        }
        stopRepository.saveAll(toSave);
    }

    private boolean pickupBeforeDropoff(List<StopPlan> stops, UUID orderId) {
        int pickup = -1;
        int dropoff = -1;
        for (int i = 0; i < stops.size(); i++) {
            StopPlan stop = stops.get(i);
            if (orderId.equals(stop.orderId())) {
                if (stop.stopType() == RouteStopType.PICKUP) {
                    pickup = i;
                } else if (stop.stopType() == RouteStopType.DROPOFF) {
                    dropoff = i;
                }
            }
        }
        return pickup >= 0 && dropoff > pickup;
    }

    private boolean isEligible(CourierProfileClient.CourierProfileSnapshot profile) {
        return "ACTIVE".equals(profile.employmentStatus())
                && profile.verified()
                && profile.canTakeOrders()
                && profile.transportType() != null
                && !profile.transportType().isBlank();
    }

    private int demandUnits(OrderGrpcClient.OrderSnapshot order) {
        ParcelSize size = order.parcelSize();
        if (size == null || size == ParcelSize.PARCEL_SIZE_UNSPECIFIED) {
            int quantity = order.itemQuantity();
            if (quantity <= 1) {
                return 1;
            }
            if (quantity <= 3) {
                return 2;
            }
            return 4;
        }
        return switch (size) {
            case SMALL -> 1;
            case MEDIUM -> 2;
            case LARGE -> 4;
            case PARCEL_SIZE_UNSPECIFIED -> 1;
            case UNRECOGNIZED -> 1;
        };
    }

    private int vehicleCapacity(String transportType) {
        return switch (transportType) {
            case "FOOT" -> 1;
            case "BIKE" -> 3;
            case "SCOOTER" -> 4;
            case "CAR" -> 10;
            case "VAN" -> 30;
            default -> 1;
        };
    }

    private double routeDistance(Coordinate start, List<StopPlan> stops) {
        double total = 0.0;
        Coordinate cursor = start;
        for (StopPlan stop : stops) {
            total += haversineMeters(cursor, stop.coordinate());
            cursor = stop.coordinate();
        }
        return total;
    }

    private double haversineMeters(Coordinate a, Coordinate b) {
        double earthRadius = 6_371_000.0;
        double dLat = Math.toRadians(b.latitude() - a.latitude());
        double dLon = Math.toRadians(b.longitude() - a.longitude());
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());
        double sinLat = Math.sin(dLat / 2);
        double sinLon = Math.sin(dLon / 2);
        double h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        return earthRadius * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    private int estimateEtaMinutes(double distanceMeters) {
        return Math.max(1, (int) Math.ceil(distanceMeters / AVERAGE_COURIER_SPEED_METERS_PER_MINUTE));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record Coordinate(double latitude, double longitude) {}

    private record StopPlan(
            RouteStop existingStop,
            UUID orderId,
            RouteStopType stopType,
            RouteStopStatus status,
            Coordinate coordinate
    ) {
        static StopPlan existing(RouteStop stop) {
            return new StopPlan(stop, stop.getOrderId(), stop.getStopType(), stop.getStatus(),
                    new Coordinate(stop.getLatitude(), stop.getLongitude()));
        }

        static StopPlan newStop(UUID orderId, RouteStopType type, Coordinate coordinate) {
            return new StopPlan(null, orderId, type, RouteStopStatus.PENDING, coordinate);
        }
    }

    private record InsertionPlan(
            Coordinate courierLocation,
            List<StopPlan> stops,
            double addedRouteDistanceMeters
    ) {}

    private record CandidatePlan(
            UUID courierId,
            int maxCapacityUnits,
            int currentLoadUnits,
            int activeOrdersCount,
            int maxActiveOrders,
            AssignmentPolicy assignmentPolicy,
            AssignmentStatus assignmentStatus,
            InsertionPlan insertionPlan,
            double score,
            double addedRouteDistanceMeters,
            double distanceToPickupMeters,
            double capacityUsagePenalty,
            OffsetDateTime locationUpdatedAt
    ) {}
}
