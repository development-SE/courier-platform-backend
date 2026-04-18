package kz.courier.apigateway.grpc;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import kz.courier.apigateway.dto.request.order.CreateOrderRequestDto;
import kz.courier.apigateway.dto.request.order.OrderListFilterDto;
import kz.courier.apigateway.dto.request.order.UpdateOrderStatusRequestDto;
import kz.courier.apigateway.dto.response.ApiResponse;
import kz.courier.common.v1.PaginationRequest;
import kz.courier.order.v1.*;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * gRPC client facade for the order-service.
 *
 * <h3>Auth design</h3>
 * Every public method accepts an explicit {@link AuthContext} carrying the caller's
 * {@code userId}, {@code roles}, and raw {@code token}.  A fresh
 * {@link AuthForwardingInterceptor} is attached to a <em>per-request copy</em> of the stub
 * via {@code withInterceptors(...)}.  This is:
 * <ul>
 *   <li><b>Thread-safe</b> — no {@code ThreadLocal}, no shared mutable state.</li>
 *   <li><b>Reactive-friendly</b> — safe to call from Spring WebFlux dispatchers.</li>
 *   <li><b>Explicit</b> — the compiler forces callers to supply auth; accidental
 *       unauthenticated calls are impossible to overlook.</li>
 * </ul>
 */
@Slf4j
@Service
public class OrderClient {

    @GrpcClient("order-service")
    private OrderServiceGrpc.OrderServiceBlockingStub orderStub;

    // ── Stub helper ───────────────────────────────────────────────────────────

    /**
     * Returns a stub decorated with a per-request {@link AuthForwardingInterceptor}
     * that will attach {@code x-user-id}, {@code x-user-roles}, and optionally
     * {@code Authorization} metadata to the outbound gRPC call.
     */
    private OrderServiceGrpc.OrderServiceBlockingStub authStub(AuthContext auth) {
        return orderStub.withInterceptors(
                new AuthForwardingInterceptor(auth.userId(), auth.roles(), auth.token(), auth.companyId()));
    }

    // ── Endpoints ─────────────────────────────────────────────────────────────

    public ApiResponse<Map<String, Object>> createOrder(CreateOrderRequestDto request, AuthContext auth) {
        try {
            log.info("gRPC CreateOrder request");

            CreateOrderRequest.Builder grpcReq = CreateOrderRequest.newBuilder()
                    .setServiceType(ServiceType.valueOf(request.getServiceType()))
                    .setComment(request.getComment() != null ? request.getComment() : "");
            if (request.getCompanyId() != null && !request.getCompanyId().isBlank()) {
                grpcReq.setCompanyId(request.getCompanyId());
            }

            if (request.getItems() != null) {
                grpcReq.addAllItems(request.getItems().stream().map(i -> {
                    var b = OrderItem.newBuilder()
                            .setItemId(i.getItemId() != null ? i.getItemId() : "")
                            .setName(i.getName())
                            .setQuantity(i.getQuantity());
                    if (i.getPrice() != null) b.setPrice(i.getPrice());
                    return b.build();
                }).collect(Collectors.toList()));
            }

            if (request.getDeliveryAddress() != null) {
                var a = request.getDeliveryAddress();
                var b = Address.newBuilder()
                        .setType(AddressType.valueOf(a.getType()))
                        .setCity(a.getCity())
                        .setStreet(a.getStreet())
                        .setHouse(a.getHouse())
                        .setLatitude(a.getLatitude())
                        .setLongitude(a.getLongitude());
                if (a.getApartment() != null) b.setApartment(a.getApartment());
                if (a.getEntrance()  != null) b.setEntrance(a.getEntrance());
                if (a.getFloor()     != null) b.setFloor(a.getFloor());
                grpcReq.setDeliveryAddress(b.build());
            }

            if (request.getRecipientInfo() != null) {
                var c = request.getRecipientInfo();
                var b = ContactInfo.newBuilder()
                        .setName(c.getName())
                        .setPhone(c.getPhone());
                if (c.getSurname() != null) b.setSurname(c.getSurname());
                grpcReq.setRecipientInfo(b.build());
            }

            if (request.getPickupAddress() != null) {
                var a = request.getPickupAddress();
                var b = Address.newBuilder()
                        .setType(AddressType.valueOf(a.getType()))
                        .setCity(a.getCity())
                        .setStreet(a.getStreet())
                        .setHouse(a.getHouse())
                        .setLatitude(a.getLatitude())
                        .setLongitude(a.getLongitude());
                if (a.getApartment() != null) b.setApartment(a.getApartment());
                if (a.getEntrance()  != null) b.setEntrance(a.getEntrance());
                if (a.getFloor()     != null) b.setFloor(a.getFloor());
                grpcReq.setPickupAddress(b.build());
            }

            if (request.getPickupInfo() != null) {
                var c = request.getPickupInfo();
                var b = ContactInfo.newBuilder()
                        .setName(c.getName())
                        .setPhone(c.getPhone());
                if (c.getSurname() != null) b.setSurname(c.getSurname());
                grpcReq.setPickupInfo(b.build());
            }

            CreateOrderResponse grpcResponse = authStub(auth).createOrder(grpcReq.build());

            if (grpcResponse.hasResponse() && !grpcResponse.getResponse().getSuccess()) {
                log.warn("Create order failed: {}", grpcResponse.getResponse().getError().getMessage());
                return ApiResponse.error(
                        grpcResponse.getResponse().getError().getCode(),
                        grpcResponse.getResponse().getError().getMessage()
                );
            }

            return ApiResponse.success(Map.of(
                    "orderId", grpcResponse.getOrderId(),
                    "status",  "NEW"
            ));

        } catch (StatusRuntimeException e) {
            return handleGrpcError(e);
        } catch (Exception e) {
            log.error("Unexpected error creating order", e);
            return ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred");
        }
    }

    public ApiResponse<Map<String, Object>> getOrder(String orderId, AuthContext auth) {
        try {
            log.info("gRPC GetOrder request for id: {}", orderId);

            GetOrderResponse grpcResponse = authStub(auth).getOrder(
                    GetOrderRequest.newBuilder().setOrderId(orderId).build());

            if (grpcResponse.hasResponse() && !grpcResponse.getResponse().getSuccess()) {
                return ApiResponse.error(
                        grpcResponse.getResponse().getError().getCode(),
                        grpcResponse.getResponse().getError().getMessage()
                );
            }

            Map<String, Object> data = new java.util.HashMap<>();
            data.put("orderId", grpcResponse.getOrderId());
            data.put("status", grpcResponse.getStatus().name());
            data.put("serviceType", grpcResponse.getServiceType().name());
            data.put("comment", grpcResponse.getComment());
            if (grpcResponse.hasDeliveryConfirmationCode()) {
                data.put("deliveryConfirmationCode", grpcResponse.getDeliveryConfirmationCode());
            }

            return ApiResponse.success(data);

        } catch (StatusRuntimeException e) {
            return handleGrpcError(e);
        } catch (Exception e) {
            log.error("Unexpected error getting order", e);
            return ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred");
        }
    }

    public ApiResponse<Map<String, Object>> updateOrderStatus(
            String orderId, UpdateOrderStatusRequestDto request, AuthContext auth) {
        try {
            log.info("gRPC UpdateOrderStatus request for id: {}, status: {}", orderId, request.getNewStatus());

            UpdateOrderStatusRequest.Builder req = UpdateOrderStatusRequest.newBuilder()
                    .setOrderId(orderId)
                    .setNewStatus(OrderStatus.valueOf(request.getNewStatus()));

            if (request.getReason() != null) req.setReason(request.getReason());

            UpdateOrderStatusResponse grpcResponse = authStub(auth).updateOrderStatus(req.build());

            if (grpcResponse.hasResponse() && !grpcResponse.getResponse().getSuccess()) {
                return ApiResponse.error(
                        grpcResponse.getResponse().getError().getCode(),
                        grpcResponse.getResponse().getError().getMessage()
                );
            }

            return ApiResponse.success(Map.of(
                    "currentStatus", grpcResponse.getCurrentStatus().name()
            ));

        } catch (StatusRuntimeException e) {
            return handleGrpcError(e);
        } catch (Exception e) {
            log.error("Unexpected error updating order status", e);
            return ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred");
        }
    }

    public ApiResponse<Map<String, Object>> listOrders(
            OrderListFilterDto filter, AuthContext auth) {
        try {
            log.info("gRPC ListOrders request");

            ListOrdersRequest.Builder req = ListOrdersRequest.newBuilder()
                    .setPagination(PaginationRequest.newBuilder()
                            .setPage(filter.getPage())
                            .setPageSize(filter.getSize())
                            .setSortBy(filter.getSortBy())
                            .setAscending(!filter.isSortDesc())
                            .build());

            if (filter.getUserId() != null && !filter.getUserId().isBlank()) req.setUserId(filter.getUserId());
            if (filter.getCompanyId() != null && !filter.getCompanyId().isBlank()) req.setCompanyId(filter.getCompanyId());
            if (filter.getStatus() != null && !filter.getStatus().isBlank()) req.setStatus(OrderStatus.valueOf(filter.getStatus()));
            if (filter.getFromDate() != null) req.setCreatedAfter(toTimestamp(filter.getFromDate()));
            if (filter.getToDate() != null) req.setCreatedBefore(toTimestamp(filter.getToDate()));
            if (filter.getMinAmount() != null) req.setMinAmount(filter.getMinAmount());
            if (filter.getMaxAmount() != null) req.setMaxAmount(filter.getMaxAmount());
            req.setSortBy(filter.getSortBy());
            req.setSortDesc(filter.isSortDesc());

            ListOrdersResponse grpcResponse = authStub(auth).listOrders(req.build());

            if (grpcResponse.hasResponse() && !grpcResponse.getResponse().getSuccess()) {
                return ApiResponse.error(
                        grpcResponse.getResponse().getError().getCode(),
                        grpcResponse.getResponse().getError().getMessage()
                );
            }

            List<Map<String, String>> orders = grpcResponse.getOrdersList().stream()
                    .map(o -> Map.of(
                            "orderId", o.getOrderId(),
                            "status",  o.getStatus().name(),
                            "companyId", o.hasCompanyId() ? o.getCompanyId() : "",
                            "totalAmount", String.valueOf(o.getTotalAmount())
                    ))
                    .toList();

            return ApiResponse.success(Map.of(
                    "orders",     orders,
                    "totalCount", grpcResponse.getTotalCount(),
                    "pagination", Map.of(
                            "currentPage", grpcResponse.getPagination().getCurrentPage(),
                            "pageSize", grpcResponse.getPagination().getPageSize(),
                            "totalPages", grpcResponse.getPagination().getTotalPages(),
                            "totalItems", grpcResponse.getPagination().getTotalItems()
                    )
            ));

        } catch (StatusRuntimeException e) {
            return handleGrpcError(e);
        } catch (Exception e) {
            log.error("Unexpected error listing orders", e);
            return ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred");
        }
    }

    public ApiResponse<Map<String, Object>> getDeliveryConfirmationCode(String orderId, AuthContext auth) {
        try {
            log.info("gRPC GetDeliveryConfirmationCode request for id: {}", orderId);

            GetDeliveryConfirmationCodeResponse grpcResponse = authStub(auth).getDeliveryConfirmationCode(
                    GetDeliveryConfirmationCodeRequest.newBuilder()
                            .setOrderId(orderId)
                            .build());

            if (grpcResponse.hasResponse() && !grpcResponse.getResponse().getSuccess()) {
                return ApiResponse.error(
                        grpcResponse.getResponse().getError().getCode(),
                        grpcResponse.getResponse().getError().getMessage()
                );
            }

            return ApiResponse.success(Map.of(
                    "confirmationCode", grpcResponse.getConfirmationCode(),
                    "expiresAt", toInstant(grpcResponse.getExpiresAt()),
                    "attemptsRemaining", grpcResponse.getAttemptsRemaining()
            ));
        } catch (StatusRuntimeException e) {
            return handleGrpcError(e);
        } catch (Exception e) {
            log.error("Unexpected error getting delivery confirmation code", e);
            return ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred");
        }
    }

    private Timestamp toTimestamp(OffsetDateTime value) {
        var instant = value.toInstant();
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private Instant toInstant(Timestamp value) {
        return Instant.ofEpochSecond(value.getSeconds(), value.getNanos());
    }

    // ── Error handling ────────────────────────────────────────────────────────

    private <T> ApiResponse<T> handleGrpcError(StatusRuntimeException e) {
        log.error("gRPC error: {}", e.getStatus());
        Status.Code code = e.getStatus().getCode();
        String msg = e.getStatus().getDescription() != null
                ? e.getStatus().getDescription()
                : e.getMessage();

        String errCode = switch (code) {
            case ALREADY_EXISTS   -> "ALREADY_EXISTS";
            case NOT_FOUND        -> "NOT_FOUND";
            case INVALID_ARGUMENT -> "INVALID_ARGUMENT";
            case PERMISSION_DENIED -> "FORBIDDEN";
            case UNAUTHENTICATED  -> "UNAUTHORIZED";
            default               -> "GRPC_ERROR_" + code.name();
        };

        return ApiResponse.error(errCode, msg);
    }
}
