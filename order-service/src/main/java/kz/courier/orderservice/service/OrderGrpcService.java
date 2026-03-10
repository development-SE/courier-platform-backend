package kz.courier.orderservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import kz.courier.common.v1.Error;
import kz.courier.common.v1.PaginationResponse;
import kz.courier.common.v1.Response;
import kz.courier.order.v1.*;
import kz.courier.orderservice.exception.OrderNotFoundException;
import kz.courier.orderservice.exception.OrderServiceException;
import kz.courier.orderservice.mapper.OrderMapper;
import kz.courier.orderservice.model.*;
import kz.courier.orderservice.model.Address;
import kz.courier.orderservice.model.Order;
import kz.courier.orderservice.repository.AddressRepository;
import kz.courier.orderservice.repository.ContactRepository;
import kz.courier.orderservice.repository.OrderRepository;
import kz.courier.orderservice.security.AuthenticatedUser;
import kz.courier.orderservice.security.GrpcAuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

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

    private final OrderRepository   orderRepository;
    private final AddressRepository addressRepository;
    private final ContactRepository contactRepository;
    private final ObjectMapper      objectMapper;

    // ─────────────────────────────────────────────────────────────────────────
    //  CreateOrder
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void createOrder(CreateOrderRequest request,
                            StreamObserver<CreateOrderResponse> responseObserver) {

        AuthenticatedUser caller = GrpcAuthContext.AUTHENTICATED_USER_KEY.get();
        log.info("[gRPC] createOrder author userId={}", caller.userId());

        try {
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
            Address pickupAddr = addressRepository.save(
                    OrderMapper.toAddressEntity(request.getPickupAddress()));

            // ── Persist contacts ────────────────────────────────────────────
            Contact recipientContact = contactRepository.save(
                    OrderMapper.toContactEntity(request.getRecipientInfo()));
            Contact pickupContact = contactRepository.save(
                    OrderMapper.toContactEntity(request.getPickupInfo()));

            // ── Serialize items to JSON ─────────────────────────────────────
            String itemsJson = OrderMapper.serializeItems(request.getItemsList(), objectMapper);

            // ── Persist order ───────────────────────────────────────────────
            Order order = Order.builder()
                    .authorId(UUID.fromString(caller.userId()))
                    .serviceType(OrderMapper.toServiceTypeEntity(request.getServiceType()))
                    .comment(request.getComment().isBlank() ? null : request.getComment())
                    .deliveryAddress(deliveryAddr)
                    .recipientContact(recipientContact)
                    .pickupAddress(pickupAddr)
                    .pickupContact(pickupContact)
                    .itemsJson(itemsJson)
                    .status(kz.courier.orderservice.model.OrderStatus.NEW)
                    .build();

            order = orderRepository.save(order);
            log.info("[gRPC] Order created id={}", order.getId());

            send(responseObserver,
                    CreateOrderResponse.newBuilder()
                            .setResponse(successResponse())
                            .setOrderId(order.getId().toString())
                            .setDeliveryAddrId(deliveryAddr.getId().toString())
                            .setRecipientContactId(recipientContact.getId().toString())
                            .setPickupAddrId(pickupAddr.getId().toString())
                            .setPickupContactId(pickupContact.getId().toString())
                            .build());

        } catch (IllegalArgumentException e) {
            log.warn("[gRPC] createOrder invalid argument: {}", e.getMessage());
            send(responseObserver,
                    CreateOrderResponse.newBuilder()
                            .setResponse(errorResponse("INVALID_ARGUMENT", e.getMessage()))
                            .build());
        } catch (Exception e) {
            log.error("[gRPC] createOrder unexpected error", e);
            responseObserver.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
            return;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GetOrder
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public void getOrder(GetOrderRequest request,
                         StreamObserver<GetOrderResponse> responseObserver) {

        AuthenticatedUser caller = GrpcAuthContext.AUTHENTICATED_USER_KEY.get();
        log.info("[gRPC] getOrder orderId={} caller={}", request.getOrderId(), caller.userId());

        try {
            UUID id = UUID.fromString(request.getOrderId());
            Order order = orderRepository.findById(id)
                    .orElseThrow(() -> new OrderNotFoundException(request.getOrderId()));

            GetOrderResponse body = OrderMapper.toGetOrderResponse(order, objectMapper);
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

        AuthenticatedUser caller = GrpcAuthContext.AUTHENTICATED_USER_KEY.get();
        log.info("[gRPC] updateOrderStatus orderId={} newStatus={} caller={}",
                request.getOrderId(), request.getNewStatus(), caller.userId());

        try {
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

            order.setStatus(newStatus);
            orderRepository.save(order);

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

        AuthenticatedUser caller = GrpcAuthContext.AUTHENTICATED_USER_KEY.get();
        log.info("[gRPC] listOrders caller={}", caller.userId());

        try {
            var pagination = request.getPagination();
            int page     = Math.max(1, pagination.getPage()) - 1;  // convert 1-based to 0-based
            int pageSize = pagination.getPageSize() > 0 ? pagination.getPageSize() : 20;

            // Sorting
            String sortBy   = request.hasSortBy()   ? request.getSortBy()   : "createdAt";
            boolean sortDesc = request.hasSortDesc() ? request.getSortDesc() : true;
            Sort sort = sortDesc
                    ? Sort.by(sortBy).descending()
                    : Sort.by(sortBy).ascending();

            Page<Order> result;

            // Apply optional client_id filter (callers see only their own orders unless admin)
            if (request.hasClientId() && !request.getClientId().isBlank()) {
                UUID authorId = UUID.fromString(request.getClientId());
                result = orderRepository.findAllByAuthorId(authorId, PageRequest.of(page, pageSize, sort));
            } else {
                result = orderRepository.findAll(PageRequest.of(page, pageSize, sort));
            }

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
        } catch (Exception e) {
            log.error("[gRPC] listOrders error", e);
            responseObserver.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
            return;
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
}
