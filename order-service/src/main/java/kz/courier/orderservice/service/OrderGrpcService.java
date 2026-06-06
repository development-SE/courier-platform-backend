package kz.courier.orderservice.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import kz.courier.common.v1.Error;
import kz.courier.common.v1.PaginationResponse;
import kz.courier.common.v1.Response;
import kz.courier.order.v1.ConfirmArrivalRequest;
import kz.courier.order.v1.ConfirmArrivalResponse;
import kz.courier.order.v1.CreateOrderRequest;
import kz.courier.order.v1.CreateOrderResponse;
import kz.courier.order.v1.GetDeliveryConfirmationCodeRequest;
import kz.courier.order.v1.GetDeliveryConfirmationCodeResponse;
import kz.courier.order.v1.GetOrderRequest;
import kz.courier.order.v1.GetOrderResponse;
import kz.courier.order.v1.ListOrdersRequest;
import kz.courier.order.v1.ListOrdersResponse;
import kz.courier.order.v1.OrderItem;
import kz.courier.order.v1.OrderServiceGrpc;
import kz.courier.order.v1.ResendDeliveryConfirmationCodeRequest;
import kz.courier.order.v1.ResendDeliveryConfirmationCodeResponse;
import kz.courier.order.v1.UpdateOrderStatusRequest;
import kz.courier.order.v1.UpdateOrderStatusResponse;
import kz.courier.order.v1.VerifyDeliveryCodeRequest;
import kz.courier.order.v1.VerifyDeliveryCodeResponse;
import kz.courier.orderservice.dto.DeliveryConfirmationCodeView;
import kz.courier.orderservice.dto.DeliveryConfirmationIssueResult;
import kz.courier.orderservice.dto.OrderFilter;
import kz.courier.orderservice.exception.OrderNotFoundException;
import kz.courier.orderservice.exception.OrderServiceException;
import kz.courier.orderservice.kafka.OrderEventPublisher;
import kz.courier.orderservice.mapper.OrderMapper;
import kz.courier.orderservice.model.Address;
import kz.courier.orderservice.model.Contact;
import kz.courier.orderservice.model.Order;
import kz.courier.orderservice.repository.AddressRepository;
import kz.courier.orderservice.repository.ContactRepository;
import kz.courier.orderservice.repository.OrderRepository;
import kz.courier.orderservice.repository.OrderSpecifications;
import kz.courier.orderservice.security.AuthenticatedUser;
import kz.courier.orderservice.security.GrpcAuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * gRPC service implementation for the OrderService definition in order.proto.
 *
 * <p>Authentication metadata is extracted from the gRPC context by {@code AuthInterceptor}
 * and accessed via {@link GrpcAuthContext#AUTHENTICATED_USER_KEY}.  The service
 * itself does <em>not</em> validate tokens — it trusts the API Gateway.
 *
 * <p>Error strategy: mirror {@code auth-service} and return a structured
 * {@link Response} with {@code success=false} rather than calling
 * {@code responseObserver.onError()} for business-level errors.
 * Only truly unexpected / infrastructure failures use {@code onError}.
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class OrderGrpcService extends OrderServiceGrpc.OrderServiceImplBase {

    private static final Set<String> PRIVILEGED_ROLES =
            Set.of("ADMIN", "SUPER_ADMIN");
    private static final Set<String> COMPANY_SCOPED_ROLES =
            Set.of("PARTNER", "DIRECTOR", "MANAGER");
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository   orderRepository;
    private final AddressRepository addressRepository;
    private final ContactRepository contactRepository;
    private final ObjectMapper      objectMapper;
    private final DeliveryConfirmationService deliveryConfirmationService;
    private final OrderEventPublisher orderEventPublisher;

    // ─────────────────────────────────────────────────────────────────────────
    //  CreateOrder
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void createOrder(CreateOrderRequest request,
                            StreamObserver<CreateOrderResponse> responseObserver) {

        try {
            AuthenticatedUser caller = requireAuthenticatedUser();
            log.info("[gRPC] createOrder author userId={}", caller.userId());

            // ── Validation ──────────────────────────────────────────────────
            if (request.getItemsList().isEmpty()) {
                send(responseObserver,
                        CreateOrderResponse.newBuilder()
                                .setResponse(errorResponse("EMPTY_ITEMS", "Order must contain at least one item"))
                                .build());
                return;
            }

            // ── Persist addresses ───────────────────────────────────────────
            Address deliveryAddr = addressRepository.save(
                    OrderMapper.toAddressEntity(request.getDeliveryAddress()));
            Address pickupAddr = OrderMapper.toAddressEntity(request.getPickupAddress());
            UUID companyId = resolveCreateCompanyId(request, caller);
            if (companyId != null) {
                pickupAddr.setCompanyId(companyId);
            }
            pickupAddr = addressRepository.save(pickupAddr);

            // ── Persist contacts ────────────────────────────────────────────
            Contact recipientContact = contactRepository.save(
                    OrderMapper.toContactEntity(request.getRecipientInfo()));
            Contact pickupContact = contactRepository.save(
                    OrderMapper.toContactEntity(request.getPickupInfo()));

            // ── Serialize items to JSON ─────────────────────────────────────
            String itemsJson = OrderMapper.serializeItems(request.getItemsList(), objectMapper);
            BigDecimal totalAmount = calculateTotalAmount(request.getItemsList());

            // ── Persist order ───────────────────────────────────────────────
            Order order = Order.builder()
                    .authorId(UUID.fromString(caller.userId()))
                    .companyId(companyId)
                    .serviceType(OrderMapper.toServiceTypeEntity(request.getServiceType()))
                    .comment(request.getComment().isBlank() ? null : request.getComment())
                    .deliveryAddress(deliveryAddr)
                    .recipientContact(recipientContact)
                    .pickupAddress(pickupAddr)
                    .pickupContact(pickupContact)
                    .itemsJson(itemsJson)
                    .totalAmount(totalAmount)
                    .parcelSize(resolveParcelSize(request))
                    .status(companyId == null
                            ? kz.courier.orderservice.model.OrderStatus.READY
                            : kz.courier.orderservice.model.OrderStatus.NEW)
                    .build();

            order = orderRepository.save(order);
            log.info("[OrderLifecycle] order created orderId={} authorId={} companyId={} status={} serviceType={}",
                    order.getId(), order.getAuthorId(), order.getCompanyId(), order.getStatus(), order.getServiceType());
            orderEventPublisher.publishCreatedAfterCommit(order);
            if (order.getStatus() == kz.courier.orderservice.model.OrderStatus.READY) {
                log.info("[OrderLifecycle] order moved to READY orderId={} reason=custom-order", order.getId());
                orderEventPublisher.publishReadyAfterCommit(order);
            }

            send(responseObserver,
                    CreateOrderResponse.newBuilder()
                            .setResponse(successResponse())
                            .setOrderId(order.getId().toString())
                            .setDeliveryAddrId(deliveryAddr.getId().toString())
                            .setRecipientContactId(recipientContact.getId().toString())
                            .setPickupAddrId(pickupAddr.getId().toString())
                            .setPickupContactId(pickupContact.getId().toString())
                            .setCurrentStatus(kz.courier.order.v1.OrderStatus.valueOf(order.getStatus().name()))
                            .setServiceType(kz.courier.order.v1.ServiceType.valueOf(order.getServiceType().name()))
                            .build());

        } catch (IllegalArgumentException e) {
            log.warn("[gRPC] createOrder invalid argument: {}", e.getMessage());
            send(responseObserver,
                    CreateOrderResponse.newBuilder()
                            .setResponse(errorResponse("INVALID_ARGUMENT", e.getMessage()))
                            .build());
        } catch (OrderServiceException e) {
            log.warn("[gRPC] createOrder rejected: {}", e.getMessage());
            send(responseObserver,
                    CreateOrderResponse.newBuilder()
                            .setResponse(errorResponse(e.getCode(), e.getMessage()))
                            .build());
        } catch (Exception e) {
            log.error("[gRPC] createOrder unexpected error", e);
            responseObserver.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GetOrder
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public void getOrder(GetOrderRequest request,
                         StreamObserver<GetOrderResponse> responseObserver) {

        try {
            AuthenticatedUser caller = requireAuthenticatedUser();
            log.info("[gRPC] getOrder orderId={} caller={}", request.getOrderId(), caller.userId());

            UUID id = UUID.fromString(request.getOrderId());
            Order order = orderRepository.findById(id)
                    .orElseThrow(() -> new OrderNotFoundException(request.getOrderId()));
            authorizeOrderRead(caller, order);

            GetOrderResponse body = OrderMapper.toGetOrderResponse(order, objectMapper);
            if (order.getStatus() == kz.courier.orderservice.model.OrderStatus.DELIVERY_CONFIRMATION_PENDING) {
                body = attachDeliveryConfirmationCodeIfAllowed(body, id, caller);
            }
            // Attach the success wrapper
            send(responseObserver,
                    body.toBuilder().setResponse(successResponse()).build());

        } catch (OrderNotFoundException e) {
            log.warn("[gRPC] getOrder not found: {}", e.getMessage());
            send(responseObserver,
                    GetOrderResponse.newBuilder()
                            .setResponse(errorResponse("ORDER_NOT_FOUND", e.getMessage()))
                            .build());
        } catch (IllegalArgumentException e) {
            send(responseObserver,
                    GetOrderResponse.newBuilder()
                            .setResponse(errorResponse("INVALID_UUID", "Invalid UUID format"))
                            .build());
        } catch (OrderServiceException e) {
            log.warn("[gRPC] getOrder forbidden: {}", e.getMessage());
            send(responseObserver,
                    GetOrderResponse.newBuilder()
                            .setResponse(errorResponse(e.getCode(), e.getMessage()))
                            .build());
        } catch (Exception e) {
            log.error("[gRPC] getOrder error", e);
            responseObserver.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
            return;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  UpdateOrderStatus
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void updateOrderStatus(UpdateOrderStatusRequest request,
                                  StreamObserver<UpdateOrderStatusResponse> responseObserver) {

        try {
            AuthenticatedUser caller = requireAuthenticatedUser();
            log.info("[gRPC] updateOrderStatus orderId={} newStatus={} caller={}",
                    request.getOrderId(), request.getNewStatus(), caller.userId());

            UUID id = UUID.fromString(request.getOrderId());
            Order order = orderRepository.findById(id)
                    .orElseThrow(() -> new OrderNotFoundException(request.getOrderId()));

            // Parse the proto status name to JPA enum
            kz.courier.order.v1.OrderStatus protoStatus = request.getNewStatus();
            if (protoStatus == kz.courier.order.v1.OrderStatus.ORDER_STATUS_UNSPECIFIED) {
                send(responseObserver,
                        UpdateOrderStatusResponse.newBuilder()
                                .setResponse(errorResponse("INVALID_STATUS", "new_status must be specified"))
                                .build());
                return;
            }

            kz.courier.orderservice.model.OrderStatus newStatus =
                    kz.courier.orderservice.model.OrderStatus.valueOf(protoStatus.name());
            authorizeStatusChange(caller, order, newStatus);

            if (newStatus == kz.courier.orderservice.model.OrderStatus.DELIVERY_CONFIRMATION_PENDING
                    || newStatus == kz.courier.orderservice.model.OrderStatus.DELIVERED) {
                send(responseObserver,
                        UpdateOrderStatusResponse.newBuilder()
                                .setResponse(errorResponse("DEDICATED_FLOW_REQUIRED",
                                        "Use logistics assignment arrival and OTP verification flow"))
                                .build());
                return;
            }

            // Guard terminal statuses
            if (order.getStatus() == kz.courier.orderservice.model.OrderStatus.DELIVERED
                    || order.getStatus() == kz.courier.orderservice.model.OrderStatus.CANCELLED
                    || order.getStatus() == kz.courier.orderservice.model.OrderStatus.REJECTED) {
                send(responseObserver,
                        UpdateOrderStatusResponse.newBuilder()
                                .setResponse(errorResponse("TERMINAL_STATUS",
                                        "Order is already in terminal status: " + order.getStatus()))
                                .build());
                return;
            }

            kz.courier.orderservice.model.OrderStatus oldStatus = order.getStatus();
            order.setStatus(newStatus);
            orderRepository.save(order);
            log.info("[OrderLifecycle] order status changed orderId={} statusFrom={} statusTo={} actorId={}",
                    order.getId(), oldStatus, newStatus, caller.userId());
            if (newStatus == kz.courier.orderservice.model.OrderStatus.CANCELLED) {
                log.info("[OrderLifecycle] order cancelled orderId={} actorId={}", order.getId(), caller.userId());
            }
            orderEventPublisher.publishStatusChangedAfterCommit(order);
            if (newStatus == kz.courier.orderservice.model.OrderStatus.READY) {
                log.info("[OrderLifecycle] order moved to READY orderId={} reason=status-update", order.getId());
                orderEventPublisher.publishReadyAfterCommit(order);
            }

            send(responseObserver,
                    UpdateOrderStatusResponse.newBuilder()
                            .setResponse(successResponse())
                            .setCurrentStatus(protoStatus)
                            .build());

        } catch (OrderNotFoundException e) {
            send(responseObserver,
                    UpdateOrderStatusResponse.newBuilder()
                            .setResponse(errorResponse("ORDER_NOT_FOUND", e.getMessage()))
                            .build());
        } catch (IllegalArgumentException e) {
            log.warn("[gRPC] updateOrderStatus invalid arg: {}", e.getMessage());
            send(responseObserver,
                    UpdateOrderStatusResponse.newBuilder()
                            .setResponse(errorResponse("INVALID_ARGUMENT", e.getMessage()))
                            .build());
        } catch (OrderServiceException e) {
            log.warn("[gRPC] updateOrderStatus forbidden: {}", e.getMessage());
            send(responseObserver,
                    UpdateOrderStatusResponse.newBuilder()
                            .setResponse(errorResponse(e.getCode(), e.getMessage()))
                            .build());
        } catch (Exception e) {
            log.error("[gRPC] updateOrderStatus error", e);
            responseObserver.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
            return;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ListOrders
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public void listOrders(ListOrdersRequest request,
                           StreamObserver<ListOrdersResponse> responseObserver) {

        try {
            AuthenticatedUser caller = requireAuthenticatedUser();

            var pagination = request.getPagination();
            int page = Math.max(1, pagination.getPage()) - 1;  // convert 1-based to 0-based
            int pageSize = resolvePageSize(pagination.getPageSize());

            // Sorting
            String sortBy   = mapSortField(request.hasSortBy() ? request.getSortBy() : "createdAt");
            boolean sortDesc = request.hasSortDesc() ? request.getSortDesc() : true;
            Sort sort = sortDesc
                    ? Sort.by(sortBy).descending()
                    : Sort.by(sortBy).ascending();

            OrderFilter requestedFilter = toOrderFilter(request);
            OrderFilter scopedFilter = applyVisibilityScope(requestedFilter, caller);

            log.info("[gRPC] listOrders caller={} roles={} filter={} page={} size={} sort={} desc={}",
                    caller.userId(), caller.roles(), scopedFilter, page + 1, pageSize, sortBy, sortDesc);

            Page<Order> result = orderRepository.findAll(
                    OrderSpecifications.byFilter(scopedFilter),
                    PageRequest.of(page, pageSize, sort));

            var listBuilder = ListOrdersResponse.newBuilder()
                    .setResponse(successResponse())
                    .setTotalCount((int) result.getTotalElements())
                    .setPagination(PaginationResponse.newBuilder()
                            .setTotalItems((int) result.getTotalElements())
                            .setCurrentPage(page + 1)
                            .setPageSize(pageSize)
                            .setTotalPages(result.getTotalPages())
                            .build());

            result.getContent().forEach(o ->
                    listBuilder.addOrders(OrderMapper.toGetOrderResponse(o, objectMapper)));

            send(responseObserver, listBuilder.build());

        } catch (IllegalArgumentException e) {
            log.warn("[gRPC] listOrders invalid arg: {}", e.getMessage());
            send(responseObserver,
                    ListOrdersResponse.newBuilder()
                            .setResponse(errorResponse("INVALID_ARGUMENT", e.getMessage()))
                            .build());
        } catch (OrderServiceException e) {
            log.warn("[gRPC] listOrders forbidden: {}", e.getMessage());
            send(responseObserver,
                    ListOrdersResponse.newBuilder()
                            .setResponse(errorResponse(e.getCode(), e.getMessage()))
                            .build());
        } catch (Exception e) {
            log.error("[gRPC] listOrders error", e);
            responseObserver.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
            return;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Delivery confirmation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Admin-only escape hatch for issuing a delivery OTP directly from
     * order-service. The normal production path is event-driven: courier marks
     * assignment ARRIVED in logistics-service, then order-service consumes the
     * assignment event and issues OTP.
     */
    @Override
    @Transactional
    public void confirmArrival(ConfirmArrivalRequest request,
                               StreamObserver<ConfirmArrivalResponse> responseObserver) {
        try {
            AuthenticatedUser caller = requireAuthenticatedUser();
            UUID orderId = UUID.fromString(request.getOrderId());
            log.info("[gRPC] confirmArrival orderId={} caller={}", orderId, caller.userId());

            DeliveryConfirmationIssueResult result =
                    deliveryConfirmationService.confirmArrival(orderId, caller);

            send(responseObserver,
                    ConfirmArrivalResponse.newBuilder()
                            .setResponse(successResponse())
                            .setCurrentStatus(kz.courier.order.v1.OrderStatus.valueOf(result.status().name()))
                            .setCodeExpiresAt(toTimestamp(result.expiresAt()))
                            .build());
        } catch (OrderNotFoundException e) {
            send(responseObserver,
                    ConfirmArrivalResponse.newBuilder()
                            .setResponse(errorResponse("ORDER_NOT_FOUND", e.getMessage()))
                            .build());
        } catch (IllegalArgumentException e) {
            send(responseObserver,
                    ConfirmArrivalResponse.newBuilder()
                            .setResponse(errorResponse("INVALID_ARGUMENT", e.getMessage()))
                            .build());
        } catch (OrderServiceException e) {
            log.warn("[gRPC] confirmArrival rejected: {}", e.getMessage());
            send(responseObserver,
                    ConfirmArrivalResponse.newBuilder()
                            .setResponse(errorResponse(e.getCode(), e.getMessage()))
                            .build());
        } catch (Exception e) {
            log.error("[gRPC] confirmArrival error", e);
            responseObserver.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
        }
    }

    /**
     * Verifies courier-entered OTP. In the public API this is called through
     * logistics-service, which owns assignment authorization. Successful
     * verification is the only path from DELIVERY_CONFIRMATION_PENDING to
     * DELIVERED.
     */
    @Override
    @Transactional
    public void verifyDeliveryCode(VerifyDeliveryCodeRequest request,
                                   StreamObserver<VerifyDeliveryCodeResponse> responseObserver) {
        try {
            AuthenticatedUser caller = requireAuthenticatedUser();
            UUID orderId = UUID.fromString(request.getOrderId());
            log.info("[gRPC] verifyDeliveryCode orderId={} caller={}", orderId, caller.userId());

            kz.courier.orderservice.model.OrderStatus status =
                    deliveryConfirmationService.verifyCode(
                            orderId, request.getConfirmationCode(), caller);

            send(responseObserver,
                    VerifyDeliveryCodeResponse.newBuilder()
                            .setResponse(successResponse())
                            .setCurrentStatus(kz.courier.order.v1.OrderStatus.valueOf(status.name()))
                            .build());
        } catch (IllegalArgumentException e) {
            send(responseObserver,
                    VerifyDeliveryCodeResponse.newBuilder()
                            .setResponse(errorResponse("INVALID_ARGUMENT", e.getMessage()))
                            .build());
        } catch (OrderServiceException e) {
            log.warn("[gRPC] verifyDeliveryCode rejected: {}", e.getMessage());
            send(responseObserver,
                    VerifyDeliveryCodeResponse.newBuilder()
                            .setResponse(errorResponse(e.getCode(), e.getMessage()))
                            .build());
        } catch (Exception e) {
            log.error("[gRPC] verifyDeliveryCode error", e);
            responseObserver.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
        }
    }

    /**
     * Fallback API for customer apps: returns the active OTP only to the order
     * owner while the order is waiting for delivery confirmation.
     */
    @Override
    @Transactional(readOnly = true)
    public void getDeliveryConfirmationCode(GetDeliveryConfirmationCodeRequest request,
                                            StreamObserver<GetDeliveryConfirmationCodeResponse> responseObserver) {
        try {
            AuthenticatedUser caller = requireAuthenticatedUser();
            UUID orderId = UUID.fromString(request.getOrderId());
            log.info("[gRPC] getDeliveryConfirmationCode orderId={} caller={}",
                    orderId, caller.userId());

            DeliveryConfirmationCodeView view =
                    deliveryConfirmationService.getCodeForCustomer(orderId, caller);

            send(responseObserver,
                    GetDeliveryConfirmationCodeResponse.newBuilder()
                            .setResponse(successResponse())
                            .setConfirmationCode(view.code())
                            .setExpiresAt(toTimestamp(view.expiresAt()))
                            .setAttemptsRemaining(view.attemptsRemaining())
                            .build());
        } catch (OrderNotFoundException e) {
            send(responseObserver,
                    GetDeliveryConfirmationCodeResponse.newBuilder()
                            .setResponse(errorResponse("ORDER_NOT_FOUND", e.getMessage()))
                            .build());
        } catch (IllegalArgumentException e) {
            send(responseObserver,
                    GetDeliveryConfirmationCodeResponse.newBuilder()
                            .setResponse(errorResponse("INVALID_ARGUMENT", e.getMessage()))
                            .build());
        } catch (OrderServiceException e) {
            log.warn("[gRPC] getDeliveryConfirmationCode rejected: {}", e.getMessage());
            send(responseObserver,
                    GetDeliveryConfirmationCodeResponse.newBuilder()
                            .setResponse(errorResponse(e.getCode(), e.getMessage()))
                            .build());
        } catch (Exception e) {
            log.error("[gRPC] getDeliveryConfirmationCode error", e);
            responseObserver.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
        }
    }

    /**
     * Resends the current OTP or creates a fresh code after expiration.
     * Assignment authorization is enforced by logistics-service before this RPC
     * is called through the gateway-authenticated gRPC client.
     */
    @Override
    @Transactional
    public void resendDeliveryConfirmationCode(ResendDeliveryConfirmationCodeRequest request,
                                               StreamObserver<ResendDeliveryConfirmationCodeResponse> responseObserver) {
        try {
            AuthenticatedUser caller = requireAuthenticatedUser();
            UUID orderId = UUID.fromString(request.getOrderId());
            UUID courierId = parseUuid(caller.userId(), "caller userId");
            log.info("[gRPC] resendDeliveryConfirmationCode orderId={} caller={}",
                    orderId, caller.userId());

            DeliveryConfirmationIssueResult result =
                    deliveryConfirmationService.resendCodeFromAssignment(orderId, courierId);

            send(responseObserver,
                    ResendDeliveryConfirmationCodeResponse.newBuilder()
                            .setResponse(successResponse())
                            .setCurrentStatus(kz.courier.order.v1.OrderStatus.valueOf(result.status().name()))
                            .setCodeExpiresAt(toTimestamp(result.expiresAt()))
                            .setRegenerated(result.regenerated())
                            .build());
        } catch (OrderNotFoundException e) {
            send(responseObserver,
                    ResendDeliveryConfirmationCodeResponse.newBuilder()
                            .setResponse(errorResponse("ORDER_NOT_FOUND", e.getMessage()))
                            .build());
        } catch (IllegalArgumentException e) {
            send(responseObserver,
                    ResendDeliveryConfirmationCodeResponse.newBuilder()
                            .setResponse(errorResponse("INVALID_ARGUMENT", e.getMessage()))
                            .build());
        } catch (OrderServiceException e) {
            log.warn("[gRPC] resendDeliveryConfirmationCode rejected: {}", e.getMessage());
            send(responseObserver,
                    ResendDeliveryConfirmationCodeResponse.newBuilder()
                            .setResponse(errorResponse(e.getCode(), e.getMessage()))
                            .build());
        } catch (Exception e) {
            log.error("[gRPC] resendDeliveryConfirmationCode error", e);
            responseObserver.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private <T> void send(StreamObserver<T> observer, T value) {
        observer.onNext(value);
        observer.onCompleted();
    }

    private Response successResponse() {
        return Response.newBuilder()
                .setSuccess(true)
                .setTimestamp(nowTs())
                .build();
    }

    private Response errorResponse(String code, String message) {
        return Response.newBuilder()
                .setSuccess(false)
                .setError(Error.newBuilder()
                        .setCode(code)
                        .setMessage(message)
                        .build())
                .setTimestamp(nowTs())
                .build();
    }

    private Timestamp nowTs() {
        Instant i = Instant.now();
        return Timestamp.newBuilder()
                .setSeconds(i.getEpochSecond())
                .setNanos(i.getNano())
                .build();
    }

    private Timestamp toTimestamp(OffsetDateTime value) {
        if (value == null) {
            return Timestamp.getDefaultInstance();
        }
        Instant i = value.toInstant();
        return Timestamp.newBuilder()
                .setSeconds(i.getEpochSecond())
                .setNanos(i.getNano())
                .build();
    }

    private AuthenticatedUser requireAuthenticatedUser() {
        AuthenticatedUser caller = GrpcAuthContext.AUTHENTICATED_USER_KEY.get();
        if (caller == null || caller.userId() == null || caller.userId().isBlank()) {
            throw new OrderServiceException("UNAUTHENTICATED", "Missing authenticated user context");
        }
        return caller;
    }

    private OrderFilter toOrderFilter(ListOrdersRequest request) {
        UUID userIdFromClientId = parseOptionalUuid(
                request.hasClientId() ? request.getClientId() : null,
                "client_id");
        UUID userId = parseOptionalUuid(
                request.hasUserId() ? request.getUserId() : null,
                "user_id");
        if (userId != null && userIdFromClientId != null && !userId.equals(userIdFromClientId)) {
            throw new OrderServiceException("INVALID_ARGUMENT",
                    "client_id and user_id must reference the same user when both are provided");
        }
        if (userId == null) {
            userId = userIdFromClientId;
        }

        UUID companyId = parseOptionalUuid(
                request.hasCompanyId() ? request.getCompanyId() : null,
                "company_id");

        kz.courier.orderservice.model.OrderStatus status = null;
        if (request.hasStatus()
                && request.getStatus() != kz.courier.order.v1.OrderStatus.ORDER_STATUS_UNSPECIFIED) {
            status = kz.courier.orderservice.model.OrderStatus.valueOf(request.getStatus().name());
        }

        OffsetDateTime createdAfter = request.hasCreatedAfter()
                ? toOffsetDateTime(request.getCreatedAfter(), "created_after")
                : null;
        OffsetDateTime createdBefore = request.hasCreatedBefore()
                ? toOffsetDateTime(request.getCreatedBefore(), "created_before")
                : null;
        if (createdAfter != null && createdBefore != null && createdAfter.isAfter(createdBefore)) {
            throw new OrderServiceException("INVALID_ARGUMENT",
                    "fromDate must be before or equal to toDate");
        }

        BigDecimal minAmount = request.hasMinAmount()
                ? positiveAmount(request.getMinAmount(), "minAmount")
                : null;
        BigDecimal maxAmount = request.hasMaxAmount()
                ? positiveAmount(request.getMaxAmount(), "maxAmount")
                : null;
        if (minAmount != null && maxAmount != null && minAmount.compareTo(maxAmount) > 0) {
            throw new OrderServiceException("INVALID_ARGUMENT",
                    "minAmount must be less than or equal to maxAmount");
        }

        return new OrderFilter(userId, companyId, status, createdAfter, createdBefore,
                minAmount, maxAmount);
    }

    private OrderFilter applyVisibilityScope(OrderFilter filter, AuthenticatedUser caller) {
        if (isPrivileged(caller)) {
            return filter;
        }

        UUID callerId = parseUuid(caller.userId(), "caller userId");
        if (isCompanyScoped(caller)) {
            UUID callerCompanyId = parseOptionalUuid(caller.companyId(), "caller companyId");
            if (callerCompanyId == null) {
                throw new OrderServiceException("FORBIDDEN",
                        "Company-scoped users must have companyId in JWT");
            }
            requireSameIfPresent(filter.companyId(), callerCompanyId, "companyId");
            return filter.withCompanyId(callerCompanyId);
        }

        requireSameIfPresent(filter.userId(), callerId, "userId");
        return filter.withUserId(callerId);
    }

    private UUID resolveCreateCompanyId(CreateOrderRequest request, AuthenticatedUser caller) {
        UUID requestCompanyId = parseOptionalUuid(
                request.hasCompanyId() ? request.getCompanyId() : null,
                "company_id");
        UUID callerCompanyId = parseOptionalUuid(caller.companyId(), "caller companyId");

        if (isCompanyScoped(caller)) {
            if (callerCompanyId == null) {
                throw new OrderServiceException("FORBIDDEN",
                        "Company-scoped users must have companyId in JWT");
            }
            requireSameIfPresent(requestCompanyId, callerCompanyId, "companyId");
            return callerCompanyId;
        }

        return requestCompanyId;
    }

    private BigDecimal calculateTotalAmount(java.util.List<OrderItem> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (OrderItem item : items) {
            if (item.getQuantity() <= 0) {
                throw new OrderServiceException("INVALID_ARGUMENT",
                        "Order item quantity must be greater than zero");
            }
            if (!item.hasPrice()) {
                continue;
            }
            BigDecimal lineTotal = BigDecimal.valueOf(item.getPrice())
                    .multiply(BigDecimal.valueOf(item.getQuantity()));
            total = total.add(lineTotal);
        }
        return total;
    }

    private kz.courier.orderservice.model.ParcelSize resolveParcelSize(CreateOrderRequest request) {
        if (request.hasParcelSize()
                && request.getParcelSize() != kz.courier.order.v1.ParcelSize.PARCEL_SIZE_UNSPECIFIED) {
            return kz.courier.orderservice.model.ParcelSize.valueOf(request.getParcelSize().name());
        }

        int totalQuantity = request.getItemsList().stream()
                .mapToInt(OrderItem::getQuantity)
                .sum();
        if (totalQuantity <= 1) {
            return kz.courier.orderservice.model.ParcelSize.SMALL;
        }
        if (totalQuantity <= 3) {
            return kz.courier.orderservice.model.ParcelSize.MEDIUM;
        }
        return kz.courier.orderservice.model.ParcelSize.LARGE;
    }

    private void requireSameIfPresent(UUID requestedId, UUID allowedId, String fieldName) {
        if (requestedId != null && !requestedId.equals(allowedId)) {
            throw new OrderServiceException("FORBIDDEN",
                    "You can only access orders inside your own " + fieldName + " scope");
        }
    }

    private void authorizeOrderRead(AuthenticatedUser caller, Order order) {
        if (isPrivileged(caller)) {
            return;
        }
        if (isCourier(caller)) {
            return;
        }

        UUID callerId = parseUuid(caller.userId(), "caller userId");
        boolean ownUserOrder = callerId.equals(order.getAuthorId());
        boolean ownCompanyOrder = false;
        if (isCompanyScoped(caller) && order.getCompanyId() != null) {
            UUID callerCompanyId = parseOptionalUuid(caller.companyId(), "caller companyId");
            ownCompanyOrder = callerCompanyId != null && callerCompanyId.equals(order.getCompanyId());
        }
        if (!ownUserOrder && !ownCompanyOrder) {
            throw new OrderServiceException("FORBIDDEN", "You do not have access to this order");
        }
    }

    private GetOrderResponse attachDeliveryConfirmationCodeIfAllowed(
            GetOrderResponse body,
            UUID orderId,
            AuthenticatedUser caller) {
        try {
            DeliveryConfirmationCodeView view =
                    deliveryConfirmationService.getCodeForCustomer(orderId, caller);
            return body.toBuilder()
                    .setDeliveryConfirmationCode(view.code())
                    .build();
        } catch (OrderServiceException e) {
            log.debug("[gRPC] delivery confirmation code not attached orderId={} reason={}",
                    orderId, e.getCode());
            return body;
        }
    }

    private void authorizeStatusChange(AuthenticatedUser caller,
                                       Order order,
                                       kz.courier.orderservice.model.OrderStatus newStatus) {
        if (isPrivileged(caller)) {
            return;
        }

        UUID callerId = parseUuid(caller.userId(), "caller userId");
        if (isCompanyScoped(caller)) {
            authorizeCompanyStatusChange(caller, order, newStatus);
            return;
        }

        if (newStatus != kz.courier.orderservice.model.OrderStatus.CANCELLED) {
            throw new OrderServiceException("FORBIDDEN",
                    "Regular users may only cancel their own orders");
        }
        if (!callerId.equals(order.getAuthorId())) {
            throw new OrderServiceException("FORBIDDEN", "You do not have access to modify this order");
        }
    }

    private void authorizeCompanyStatusChange(AuthenticatedUser caller,
                                              Order order,
                                              kz.courier.orderservice.model.OrderStatus newStatus) {
        if (order.getCompanyId() == null) {
            throw new OrderServiceException("FORBIDDEN",
                    "Company users can only prepare company orders");
        }

        UUID callerCompanyId = parseOptionalUuid(caller.companyId(), "caller companyId");
        if (callerCompanyId == null || !callerCompanyId.equals(order.getCompanyId())) {
            throw new OrderServiceException("FORBIDDEN",
                    "Company users can only modify orders for their own company");
        }

        if (!isCompanyPreparationTransition(order.getStatus(), newStatus)) {
            throw new OrderServiceException("INVALID_TRANSITION",
                    "Company order transition " + order.getStatus() + " -> " + newStatus + " is not allowed");
        }
    }

    private boolean isCompanyPreparationTransition(kz.courier.orderservice.model.OrderStatus current,
                                                   kz.courier.orderservice.model.OrderStatus next) {
        return switch (current) {
            case NEW -> next == kz.courier.orderservice.model.OrderStatus.ACCEPTED
                    || next == kz.courier.orderservice.model.OrderStatus.REJECTED
                    || next == kz.courier.orderservice.model.OrderStatus.CANCELLED;
            case ACCEPTED -> next == kz.courier.orderservice.model.OrderStatus.PREPARING
                    || next == kz.courier.orderservice.model.OrderStatus.CANCELLED
                    || next == kz.courier.orderservice.model.OrderStatus.READY;
            case PREPARING -> next == kz.courier.orderservice.model.OrderStatus.READY
                    || next == kz.courier.orderservice.model.OrderStatus.CANCELLED;
            default -> false;
        };
    }

    private boolean isPrivileged(AuthenticatedUser caller) {
        return caller != null && caller.hasRole(PRIVILEGED_ROLES.toArray(String[]::new));
    }

    private boolean isCompanyScoped(AuthenticatedUser caller) {
        return caller != null && caller.hasRole(COMPANY_SCOPED_ROLES.toArray(String[]::new));
    }

    private boolean isCourier(AuthenticatedUser caller) {
        return caller != null && caller.hasRole("COURIER");
    }

    private UUID parseUuid(String rawValue, String fieldName) {
        try {
            return UUID.fromString(rawValue);
        } catch (Exception e) {
            throw new OrderServiceException("INVALID_ARGUMENT", "Invalid UUID for " + fieldName);
        }
    }

    private UUID parseOptionalUuid(String rawValue, String fieldName) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        return parseUuid(rawValue, fieldName);
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp, String fieldName) {
        try {
            return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos())
                    .atOffset(ZoneOffset.UTC);
        } catch (Exception e) {
            throw new OrderServiceException("INVALID_ARGUMENT",
                    "Invalid timestamp for " + fieldName);
        }
    }

    private BigDecimal positiveAmount(double rawAmount, String fieldName) {
        if (rawAmount < 0) {
            throw new OrderServiceException("INVALID_ARGUMENT",
                    fieldName + " must be greater than or equal to zero");
        }
        return BigDecimal.valueOf(rawAmount);
    }

    private int resolvePageSize(int requestedPageSize) {
        int pageSize = requestedPageSize > 0 ? requestedPageSize : DEFAULT_PAGE_SIZE;
        if (pageSize > MAX_PAGE_SIZE) {
            throw new OrderServiceException("INVALID_ARGUMENT",
                    "page size must be less than or equal to " + MAX_PAGE_SIZE);
        }
        return pageSize;
    }

    private String mapSortField(String rawSortBy) {
        if (rawSortBy == null || rawSortBy.isBlank()) {
            return "createdAt";
        }

        return switch (rawSortBy) {
            case "createdAt", "created_at" -> "createdAt";
            case "updatedAt", "updated_at" -> "updatedAt";
            case "status" -> "status";
            case "serviceType", "service_type" -> "serviceType";
            case "authorId", "author_id", "userId", "user_id" -> "authorId";
            case "companyId", "company_id" -> "companyId";
            case "totalAmount", "total_amount", "amount" -> "totalAmount";
            default -> throw new OrderServiceException("INVALID_ARGUMENT",
                    "Unsupported sort field: " + rawSortBy);
        };
    }
}
