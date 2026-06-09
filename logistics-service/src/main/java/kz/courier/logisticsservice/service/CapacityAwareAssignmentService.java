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
import kz.courier.order.v1.ServiceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
                    AssignmentStatus.REJECTED, AssignmentStatus.TIMED_OUT, AssignmentStatus.MANUAL_REQUIRED);
    private static final Set<OrderStatus> AUTO_ASSIGNABLE_ORDER_STATUSES =
            EnumSet.of(OrderStatus.READY, OrderStatus.ASSIGNMENT_PENDING);
    private static final double AUTO_ASSIGN_RADIUS_METERS = 5_000.0;
    private static final int AUTO_ASSIGN_LIMIT = 20;
    private static final long MAX_LOCATION_AGE_MINUTES = 10;
    private static final double AVERAGE_COURIER_SPEED_METERS_PER_MINUTE = 250.0;
    private static final List<AssignmentStatus> TERMINAL_ASSIGNMENT_STATUSES =
            List.of(AssignmentStatus.DELIVERED, AssignmentStatus.CANCELLED, AssignmentStatus.FAILED,
                    AssignmentStatus.REJECTED, AssignmentStatus.TIMED_OUT, AssignmentStatus.MANUAL_REQUIRED);

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
    private final RouteCleanupService routeCleanupService;
    private final AssignmentMetrics assignmentMetrics;
    private final RoutingServiceClient routingServiceClient;

    @Value("${assignment.retry.delay-seconds:60}")
    private long retryDelaySeconds;

    @Value("${assignment.employee-search-timeout-seconds:120}")
    private long employeeSearchTimeoutSeconds;

    @Value("${assignment.standard-batching-window-seconds:0}")
    private long standardBatchingWindowSeconds;

    @Transactional
    public LogisticsDto.AutoAssignResponse autoAssign(UUID orderId) {
        var timer = assignmentMetrics.startAssignmentTimer();
        String result = "failed";
        try {
            LogisticsDto.AutoAssignResponse response = autoAssignInternal(orderId);
            result = response.assignmentStatus() == AssignmentStatus.MANUAL_REQUIRED
                    ? "manual_required"
                    : "success";
            return response;
        } catch (BusinessException ex) {
            if ("DUPLICATE_ASSIGNMENT".equals(ex.getCode())) {
                assignmentMetrics.recordDuplicatePrevented(ex.getCode());
            }
            assignmentMetrics.recordFailure("unknown", ex.getCode());
            throw ex;
        } finally {
            assignmentMetrics.recordDuration(timer, "unknown", result);
        }
    }

    private LogisticsDto.AutoAssignResponse autoAssignInternal(UUID orderId) {
        UUID actorId = gatewayPrincipalProvider.requireCurrentUserId();
        assignmentMetrics.recordAttempt("unknown", "auto");
        log.info("[AssignmentLifecycle] assignment started orderId={} mode=auto", orderId);
        assignmentRepository.lockOrderAssignmentMutex(orderId.toString());
        if (!assignmentRepository.lockActiveAssignmentsByOrderId(orderId).isEmpty()) {
            assignmentMetrics.recordDuplicatePrevented("active-assignment");
            log.info("[AssignmentLifecycle] duplicate assignment prevented orderId={} reason=active-assignment", orderId);
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
        Optional<LogisticsDto.AutoAssignResponse> waiting = maybeWaitBeforeFallbackOrBatching(order, demandUnits, actorId);
        if (waiting.isPresent()) {
            return waiting.get();
        }

        List<LogisticsDto.NearbyCourierResponse> nearbyCouriers = findNearby(order);
        assignmentMetrics.recordCandidatesFound(nearbyCouriers.size());
        Set<UUID> excludedCourierIds = exclusionSetForThisCycle(orderId, nearbyCouriers);
        AssignmentFailureReason noPlanReason = nearbyCouriers.isEmpty()
                ? AssignmentFailureReason.NO_ONLINE_COURIERS
                : allNearbyLocationsStale(nearbyCouriers)
                ? AssignmentFailureReason.STALE_LOCATIONS
                : AssignmentFailureReason.NO_CAPACITY_AVAILABLE;
        List<CandidatePlan> rankedPlans;
        try {
            rankedPlans = nearbyCouriers.stream()
                    .filter(candidate -> !excludedCourierIds.contains(candidate.courierId()))
                    .map(candidate -> evaluateCandidate(candidate, order, demandUnits))
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparingDouble(CandidatePlan::score))
                    .toList();
            assignmentMetrics.recordCandidatesEligible(rankedPlans.size());
        } catch (CourierProfileClient.CourierProfileUnavailableException ex) {
            String message = "Courier profile service is unavailable";
            CourierAssignment failed = persistManualRequired(orderId, demandUnits, nearbyCouriers.size(), 0,
                    AssignmentFailureReason.COURIER_SERVICE_UNAVAILABLE, message, actorId);
            return failureResponse(orderId, failed, nearbyCouriers.size(), 0,
                    AssignmentFailureReason.COURIER_SERVICE_UNAVAILABLE, message);
        }

        for (CandidatePlan plan : rankedPlans) {
            log.info("[AssignmentLifecycle] courier candidate selected orderId={} courierId={} assignmentPolicy={} courierType={} serviceType={}",
                    orderId, plan.courierId(), plan.assignmentPolicy(), courierType(plan.assignmentPolicy()), order.serviceType());
            Optional<CourierAssignment> assignment = tryCommitPlan(plan, order, demandUnits, actorId,
                    "capacity-aware-auto-assign");
            if (assignment.isPresent()) {
                CourierAssignment saved = assignment.get();
                assignmentMetrics.recordSuccess(order.serviceType().name(), saved.getAssignmentPolicy().name());
                logAssignmentCreated(order, saved);
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
                : noPlanReason == AssignmentFailureReason.STALE_LOCATIONS
                ? "Online couriers were found nearby, but their locations are stale"
                : "No courier had enough capacity or a feasible route insertion";
        CourierAssignment failed = persistManualRequired(orderId, demandUnits, nearbyCouriers.size(),
                rankedPlans.size(), noPlanReason, message, actorId);
        return failureResponse(orderId, failed, nearbyCouriers.size(), rankedPlans.size(),
                noPlanReason, message);
    }

    @Transactional
    public LogisticsDto.AssignmentResponse manualAssign(LogisticsDto.ManualAssignmentRequest request) {
        var timer = assignmentMetrics.startAssignmentTimer();
        String result = "failed";
        try {
            LogisticsDto.AssignmentResponse response = manualAssignInternal(request);
            result = "success";
            return response;
        } catch (BusinessException ex) {
            if ("DUPLICATE_ASSIGNMENT".equals(ex.getCode())) {
                assignmentMetrics.recordDuplicatePrevented(ex.getCode());
            }
            assignmentMetrics.recordFailure("unknown", ex.getCode());
            throw ex;
        } finally {
            assignmentMetrics.recordDuration(timer, "unknown", result);
        }
    }

    private LogisticsDto.AssignmentResponse manualAssignInternal(LogisticsDto.ManualAssignmentRequest request) {
        UUID actorId = gatewayPrincipalProvider.requireCurrentUserId();
        UUID orderId = request.orderId();
        UUID courierId = request.courierId();
        assignmentMetrics.recordAttempt("unknown", "manual");
        log.info("[AssignmentLifecycle] assignment started orderId={} courierId={} mode=manual",
                orderId, courierId);
        assignmentRepository.lockOrderAssignmentMutex(orderId.toString());
        cleanupTerminalAssignmentsBeforeManualAssignment(orderId, request.reason());

        if (!assignmentRepository.lockActiveAssignmentsByOrderId(orderId).isEmpty()) {
            assignmentMetrics.recordDuplicatePrevented("active-assignment");
            log.info("[AssignmentLifecycle] duplicate assignment prevented orderId={} reason=active-assignment", orderId);
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
        assignmentMetrics.recordSuccess(order.serviceType().name(), saved.getAssignmentPolicy().name());
        logAssignmentCreated(order, saved);
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

    private boolean allNearbyLocationsStale(List<LogisticsDto.NearbyCourierResponse> nearbyCouriers) {
        if (nearbyCouriers.isEmpty()) {
            return false;
        }
        OffsetDateTime now = OffsetDateTime.now();
        return nearbyCouriers.stream()
                .allMatch(candidate -> candidate.updatedAt() == null
                        || ChronoUnit.MINUTES.between(candidate.updatedAt(), now) > MAX_LOCATION_AGE_MINUTES);
    }

    private Optional<CandidatePlan> evaluateCandidate(
            LogisticsDto.NearbyCourierResponse candidate,
            OrderGrpcClient.OrderSnapshot order,
            int demandUnits) {

        if (!Boolean.TRUE.equals(candidate.isOnline()) || candidate.updatedAt() == null) {
            log.debug("[AssignmentCandidate] reject orderId={} courierId={} reason=OFFLINE_OR_MISSING_LOCATION online={} updatedAt={}",
                    order.orderId(), candidate.courierId(), candidate.isOnline(), candidate.updatedAt());
            return Optional.empty();
        }
        long ageMinutes = ChronoUnit.MINUTES.between(candidate.updatedAt(), OffsetDateTime.now());
        if (ageMinutes > MAX_LOCATION_AGE_MINUTES) {
            log.debug("[AssignmentCandidate] reject orderId={} courierId={} reason=STALE_LOCATION ageMinutes={} maxAgeMinutes={} updatedAt={}",
                    order.orderId(), candidate.courierId(), ageMinutes, MAX_LOCATION_AGE_MINUTES, candidate.updatedAt());
            return Optional.empty();
        }

        Optional<CourierProfileClient.CourierProfileSnapshot> profileOpt =
                courierProfileClient.getCourier(candidate.courierId());
        if (profileOpt.isEmpty()) {
            log.debug("[AssignmentCandidate] reject orderId={} courierId={} reason=PROFILE_NOT_FOUND",
                    order.orderId(), candidate.courierId());
            return Optional.empty();
        }
        EligibilityResult eligibility = eligibilityResult(profileOpt.get(), order);
        if (!eligibility.eligible()) {
            CourierProfileClient.CourierProfileSnapshot profile = profileOpt.get();
            log.debug("[AssignmentCandidate] reject orderId={} courierId={} reason={} courierType={} companyId={} employmentStatus={} verified={} canTakeOrders={} transportType={}",
                    order.orderId(), candidate.courierId(), eligibility.reason(), profile.courierType(),
                    profile.companyId(), profile.employmentStatus(), profile.verified(),
                    profile.canTakeOrders(), profile.transportType());
            return Optional.empty();
        }

        CourierProfileClient.CourierProfileSnapshot profile = profileOpt.get();
        long orderAgeSeconds = Math.max(0, ChronoUnit.SECONDS.between(order.createdAt(), OffsetDateTime.now()));
        if (order.companyId() != null
                && !"EMPLOYEE".equals(profile.courierType())
                && orderAgeSeconds < employeeSearchTimeoutSeconds) {
            log.debug("[AssignmentCandidate] reject orderId={} courierId={} reason=WAITING_FOR_COMPANY_EMPLOYEE orderCompanyId={} courierType={} orderAgeSeconds={} employeeSearchTimeoutSeconds={}",
                    order.orderId(), candidate.courierId(), order.companyId(), profile.courierType(),
                    orderAgeSeconds, employeeSearchTimeoutSeconds);
            return Optional.empty();
        }

        int maxCapacity = vehicleCapacity(profile.transportType());
        if (demandUnits > maxCapacity) {
            log.debug("[AssignmentCandidate] reject orderId={} courierId={} reason=PARCEL_TOO_LARGE demandUnits={} maxCapacity={} transportType={}",
                    order.orderId(), candidate.courierId(), demandUnits, maxCapacity, profile.transportType());
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
            log.debug("[AssignmentCandidate] reject orderId={} courierId={} reason=CAPACITY_OR_MAX_ACTIVE currentLoad={} demandUnits={} maxCapacity={} activeOrders={} maxActiveOrders={} routeId={}",
                    order.orderId(), candidate.courierId(), currentLoad, demandUnits, maxCapacity,
                    activeOrders, maxActiveOrders, routeOpt.map(CourierRoute::getId).orElse(null));
            return Optional.empty();
        }

        Coordinate courierLocation = new Coordinate(candidate.latitude(), candidate.longitude());
        Coordinate pickup = new Coordinate(order.pickupLatitude(), order.pickupLongitude());
        Coordinate dropoff = new Coordinate(order.deliveryLatitude(), order.deliveryLongitude());
        Optional<InsertionPlan> insertion = bestInsertion(routeOpt.orElse(null), existingStops,
                courierLocation, order.orderId(), pickup, dropoff);
        if (insertion.isEmpty()) {
            log.debug("[AssignmentCandidate] reject orderId={} courierId={} reason=NO_FEASIBLE_ROUTE_INSERTION existingStops={} routeId={}",
                    order.orderId(), candidate.courierId(), existingStops.size(),
                    routeOpt.map(CourierRoute::getId).orElse(null));
            return Optional.empty();
        }

        if (order.serviceType() == ServiceType.EXPRESS && activeOrders > 0) {
            log.debug("[AssignmentCandidate] reject orderId={} courierId={} reason=EXPRESS_REQUIRES_EMPTY_ROUTE activeOrders={}",
                    order.orderId(), candidate.courierId(), activeOrders);
            return Optional.empty();
        }

        double addedRouteDistance = insertion.get().addedRouteDistanceMeters();
        double distanceToPickup = haversineMeters(courierLocation, pickup);
        double activeOrdersPenalty = ((double) activeOrders / maxActiveOrders) * AUTO_ASSIGN_RADIUS_METERS;
        double capacityUsagePenalty = ((double) (currentLoad + demandUnits) / maxCapacity) * AUTO_ASSIGN_RADIUS_METERS;
        double score = scoreCandidate(order, profile, addedRouteDistance, distanceToPickup,
                activeOrdersPenalty, capacityUsagePenalty);

        AssignmentPolicy policy = "EMPLOYEE".equals(profile.courierType())
                ? AssignmentPolicy.DIRECT
                : AssignmentPolicy.OFFER;
        AssignmentStatus status = policy == AssignmentPolicy.DIRECT
                ? AssignmentStatus.ASSIGNED
                : AssignmentStatus.PENDING;

        log.debug("[AssignmentCandidate] accept orderId={} courierId={} courierType={} transportType={} policy={} status={} demandUnits={} maxCapacity={} currentLoad={} activeOrders={} score={} distanceToPickupMeters={} addedRouteDistanceMeters={}",
                order.orderId(), candidate.courierId(), profile.courierType(), profile.transportType(),
                policy, status, demandUnits, maxCapacity, currentLoad, activeOrders, round(score),
                round(distanceToPickup), round(addedRouteDistance));

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
            assignmentMetrics.recordDuplicatePrevented("active-assignment");
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

        Optional<CourierProfileClient.CourierProfileSnapshot> profileOpt =
                courierProfileClient.getCourier(plan.courierId());
        String transportType = profileOpt.map(CourierProfileClient.CourierProfileSnapshot::transportType).orElse("driving");

        OffsetDateTime now = OffsetDateTime.now();
        Map<StopPlan, OffsetDateTime> estimatedArrivalTimes = estimateArrivalTimes(insertion.get(), now, transportType);

        int pickupEtaMinutes = estimateEtaMinutes(plan.distanceToPickupMeters());
        for (Map.Entry<StopPlan, OffsetDateTime> entry : estimatedArrivalTimes.entrySet()) {
            StopPlan stopPlan = entry.getKey();
            if (order.orderId().equals(stopPlan.orderId()) && stopPlan.stopType() == RouteStopType.PICKUP) {
                long durationSec = java.time.Duration.between(now, entry.getValue()).toSeconds();
                pickupEtaMinutes = Math.max(1, (int) Math.ceil(durationSec / 60.0));
                break;
            }
        }

        persistStops(route.getId(), insertion.get(), estimatedArrivalTimes);

        CourierAssignment assignment = CourierAssignment.builder()
                .orderId(order.orderId())
                .courierId(plan.courierId())
                .routeId(route.getId())
                .assignedBy(actorId)
                .assignmentStatus(plan.assignmentStatus())
                .assignmentPolicy(plan.assignmentPolicy())
                .assignedAt(now)
                .etaMinutes(pickupEtaMinutes)
                .score(plan.score())
                .demandUnits(demandUnits)
                .build();
        try {
            assignment = assignmentRepository.saveAndFlush(assignment);
        } catch (DataIntegrityViolationException ex) {
            assignmentMetrics.recordDuplicatePrevented("db-constraint");
            log.info("[AssignmentCandidate] concurrent assignment won orderId={} courierId={} constraint={}",
                    order.orderId(), plan.courierId(), mostSpecificMessage(ex));
            throw new BusinessException("DUPLICATE_ASSIGNMENT",
                    "An active assignment already exists for order " + order.orderId());
        }

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
        assignmentMetrics.recordManualRequired(reason.name());
        assignmentMetrics.recordFailure("unknown", reason.name());
        log.info("[AssignmentLifecycle] manual-required created orderId={} assignmentId={} reason={} scannedCandidates={} eligibleCandidates={}",
                orderId, saved.getId(), reason, scannedCandidates, eligibleCandidates);
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

    private void cleanupTerminalAssignmentsBeforeManualAssignment(UUID orderId, String reason) {
        assignmentRepository.lockUncleanedAssignmentsByOrderId(orderId).stream()
                .filter(assignment -> !assignment.getAssignmentStatus().isActive())
                .forEach(assignment -> routeCleanupService.cleanupAssignment(
                        assignment,
                        reason == null || reason.isBlank() ? "manual-reassignment" : reason,
                        false));
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

    private Optional<LogisticsDto.AutoAssignResponse> maybeWaitBeforeFallbackOrBatching(
            OrderGrpcClient.OrderSnapshot order,
            int demandUnits,
            UUID actorId) {

        long ageSeconds = Math.max(0, ChronoUnit.SECONDS.between(order.createdAt(), OffsetDateTime.now()));
        if (order.serviceType() == ServiceType.STANDARD
                && order.status() == OrderStatus.READY
                && standardBatchingWindowSeconds > 0
                && ageSeconds < standardBatchingWindowSeconds) {
            long remaining = standardBatchingWindowSeconds - ageSeconds;
            String message = "STANDARD order is waiting for batching window";
            CourierAssignment failed = persistManualRequired(order.orderId(), demandUnits, 0, 0,
                    AssignmentFailureReason.NO_CAPACITY_AVAILABLE, message, actorId);
            failed.setNextRetryAt(OffsetDateTime.now().plusSeconds(remaining));
            assignmentRepository.save(failed);
            return Optional.of(failureResponse(order.orderId(), failed, 0, 0,
                    AssignmentFailureReason.NO_CAPACITY_AVAILABLE, message));
        }

        return Optional.empty();
    }

    private Set<UUID> exclusionSetForThisCycle(UUID orderId, List<LogisticsDto.NearbyCourierResponse> nearbyCouriers) {
        Set<UUID> excludedCourierIds = new HashSet<>(assignmentRepository.findExcludedCourierIdsByOrderId(orderId));
        if (excludedCourierIds.isEmpty() || nearbyCouriers.isEmpty()) {
            return excludedCourierIds;
        }

    // Stale couriers are rejected by evaluateCandidate() regardless — only count fresh ones
        // when deciding whether all viable couriers have already been tried for this cycle.
        OffsetDateTime now = OffsetDateTime.now();
        Set<UUID> freshNearbyCourierIds = nearbyCouriers.stream()
                .filter(c -> c.updatedAt() != null
                        && ChronoUnit.MINUTES.between(c.updatedAt(), now) <= MAX_LOCATION_AGE_MINUTES)
                .map(LogisticsDto.NearbyCourierResponse::courierId)
                .collect(java.util.stream.Collectors.toSet());

        if (!freshNearbyCourierIds.isEmpty() && !excludedCourierIds.containsAll(freshNearbyCourierIds)) {
            // There are fresh couriers that haven't been offered this order yet
            return excludedCourierIds;
        }

        long totalExcluded = assignmentRepository.countExcludedCourierIdsByOrderId(orderId);
         log.info("[AssignmentCycle] all fresh nearby couriers already rejected/timed out orderId={} freshNearbyCouriers={} totalExcluded={}; starting another offer cycle",
                orderId, freshNearbyCourierIds.size(), totalExcluded);
        return Set.of();
    }

    private double scoreCandidate(OrderGrpcClient.OrderSnapshot order,
                                  CourierProfileClient.CourierProfileSnapshot profile,
                                  double addedRouteDistance,
                                  double distanceToPickup,
                                  double activeOrdersPenalty,
                                  double capacityUsagePenalty) {
        if (order.serviceType() == ServiceType.EXPRESS) {
            return round(distanceToPickup * 0.75
                    + activeOrdersPenalty * 0.15
                    + capacityUsagePenalty * 0.10);
        }

        double score = addedRouteDistance * 0.45
                + activeOrdersPenalty * 0.20
                + capacityUsagePenalty * 0.20
                + distanceToPickup * 0.15;

        if (order.companyId() != null && "EMPLOYEE".equals(profile.courierType())
                && order.companyId().equals(profile.companyId())) {
            score -= AUTO_ASSIGN_RADIUS_METERS * 0.50;
        }

        long ageSeconds = Math.max(0, ChronoUnit.SECONDS.between(order.createdAt(), OffsetDateTime.now()));
        if (order.companyId() != null
                && !"EMPLOYEE".equals(profile.courierType())
                && ageSeconds < employeeSearchTimeoutSeconds) {
            score += AUTO_ASSIGN_RADIUS_METERS * 10;
        }

        return round(Math.max(0.0, score));
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

    private void persistStops(UUID routeId, InsertionPlan insertion, Map<StopPlan, OffsetDateTime> estimatedArrivalTimes) {
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
            stop.setEstimatedArrivalTime(estimatedArrivalTimes.get(plan));
            toSave.add(stop);
        }
        stopRepository.saveAll(toSave);
    }

    private Map<StopPlan, OffsetDateTime> estimateArrivalTimes(InsertionPlan insertion, OffsetDateTime baseTime, String transportType) {
        Map<StopPlan, OffsetDateTime> estimates = new IdentityHashMap<>();
        
        List<RoutingServiceClient.Coordinate> routeCoords = new ArrayList<>();
        routeCoords.add(new RoutingServiceClient.Coordinate(insertion.courierLocation().latitude(), insertion.courierLocation().longitude()));
        for (StopPlan stop : insertion.stops()) {
            routeCoords.add(new RoutingServiceClient.Coordinate(stop.coordinate().latitude(), stop.coordinate().longitude()));
        }

        RoutingServiceClient.RouteResult routeResult = routingServiceClient.calculateRoute(transportType, routeCoords);

        if (routeResult.success() && routeResult.legs().size() == insertion.stops().size()) {
            OffsetDateTime currentTime = baseTime;
            for (int i = 0; i < insertion.stops().size(); i++) {
                StopPlan stop = insertion.stops().get(i);
                RoutingServiceClient.RouteLegResult leg = routeResult.legs().get(i);
                int legMinutes = Math.max(1, (int) Math.ceil(leg.durationSeconds() / 60.0));
                currentTime = currentTime.plusMinutes(legMinutes);
                estimates.put(stop, currentTime);
            }
        } else {
            Coordinate cursor = insertion.courierLocation();
            double cumulativeMeters = 0.0;
            for (StopPlan stop : insertion.stops()) {
                cumulativeMeters += haversineMeters(cursor, stop.coordinate());
                estimates.put(stop, baseTime.plusMinutes(estimateEtaMinutes(cumulativeMeters)));
                cursor = stop.coordinate();
            }
        }
        return estimates;
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

    private boolean isEligible(CourierProfileClient.CourierProfileSnapshot profile,
                               OrderGrpcClient.OrderSnapshot order) {
        return eligibilityResult(profile, order).eligible();
    }

    private EligibilityResult eligibilityResult(CourierProfileClient.CourierProfileSnapshot profile,
                                                OrderGrpcClient.OrderSnapshot order) {
        boolean profileAllowed = "ACTIVE".equals(profile.employmentStatus())
                && profile.verified()
                && profile.canTakeOrders()
                && profile.transportType() != null
                && !profile.transportType().isBlank();
        if (!profileAllowed) {
            return new EligibilityResult(false, "PROFILE_NOT_ALLOWED");
        }
        if (order.companyId() != null && "EMPLOYEE".equals(profile.courierType())
                && !order.companyId().equals(profile.companyId())) {
            return new EligibilityResult(false, "EMPLOYEE_COMPANY_MISMATCH");
        }
        if (!"EMPLOYEE".equals(profile.courierType())) {
            return new EligibilityResult(true, "CONTRACTOR_AVAILABLE");
        }
        return courierProfileClient.getEligibility(profile.courierId())
                .map(eligibility -> new EligibilityResult(eligibility.eligible(), eligibility.reasonCode()))
                .orElseGet(() -> new EligibilityResult(false, "EMPLOYEE_ELIGIBILITY_UNKNOWN"));
    }

    private record EligibilityResult(boolean eligible, String reason) {}

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

    private String mostSpecificMessage(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        return cause == null ? ex.getMessage() : cause.getMessage();
    }

    private void logAssignmentCreated(OrderGrpcClient.OrderSnapshot order, CourierAssignment assignment) {
        if (assignment.getAssignmentPolicy() == AssignmentPolicy.DIRECT) {
            log.info("[AssignmentLifecycle] employee direct assignment created orderId={} assignmentId={} courierId={} routeId={} serviceType={}",
                    assignment.getOrderId(), assignment.getId(), assignment.getCourierId(),
                    assignment.getRouteId(), order.serviceType());
            return;
        }
        log.info("[AssignmentLifecycle] contractor offer created orderId={} assignmentId={} courierId={} routeId={} serviceType={}",
                assignment.getOrderId(), assignment.getId(), assignment.getCourierId(),
                assignment.getRouteId(), order.serviceType());
    }

    private String courierType(AssignmentPolicy policy) {
        return policy == AssignmentPolicy.DIRECT ? "employee" : "contractor";
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
