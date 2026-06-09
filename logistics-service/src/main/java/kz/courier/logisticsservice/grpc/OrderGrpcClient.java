package kz.courier.logisticsservice.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import kz.courier.common.v1.PaginationRequest;
import kz.courier.logisticsservice.exception.BusinessException;
import kz.courier.logisticsservice.security.GatewayPrincipalProvider;
import kz.courier.order.v1.GetOrderRequest;
import kz.courier.order.v1.GetOrderResponse;
import kz.courier.order.v1.ListOrdersRequest;
import kz.courier.order.v1.ListOrdersResponse;
import kz.courier.order.v1.OrderServiceGrpc;
import kz.courier.order.v1.OrderStatus;
import kz.courier.order.v1.ParcelSize;
import kz.courier.order.v1.ResendDeliveryConfirmationCodeRequest;
import kz.courier.order.v1.ResendDeliveryConfirmationCodeResponse;
import kz.courier.order.v1.ServiceType;
import kz.courier.order.v1.UpdateOrderStatusRequest;
import kz.courier.order.v1.UpdateOrderStatusResponse;
import kz.courier.order.v1.VerifyDeliveryCodeRequest;
import kz.courier.order.v1.VerifyDeliveryCodeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Reads order snapshots from {@code order-service}.
 *
 * <p>The auto-assignment flow needs pickup coordinates owned by order-service.
 * Logistics fetches that data synchronously over gRPC and then performs the
 * courier ranking and assignment transaction locally.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderGrpcClient {

    private final GatewayPrincipalProvider gatewayPrincipalProvider;

    @GrpcClient("order-service")
    private OrderServiceGrpc.OrderServiceBlockingStub orderStub;

    /**
     * Loads the minimal order snapshot required for dispatch calculations.
     */
    public OrderSnapshot getOrder(UUID orderId) {
        GatewayPrincipalProvider.GatewayPrincipal principal =
                gatewayPrincipalProvider.requireCurrentPrincipal();

        try {
            log.info("Requesting order snapshot from order-service orderId={} caller={}",
                    orderId, principal.userId());

            GetOrderResponse response = authenticatedStub(principal).getOrder(
                    GetOrderRequest.newBuilder()
                            .setOrderId(orderId.toString())
                            .build());

            if (response.hasResponse() && !response.getResponse().getSuccess()) {
                String code = response.getResponse().getError().getCode();
                String message = response.getResponse().getError().getMessage();
                throw new BusinessException(code != null ? code : "ORDER_SERVICE_ERROR",
                        message != null ? message : "order-service rejected the request");
            }

            if (!response.hasPickupAddress()) {
                throw new BusinessException("ORDER_PICKUP_LOCATION_MISSING",
                        "Order " + orderId + " does not have pickup coordinates");
            }
            if (!response.hasDeliveryAddress()) {
                throw new BusinessException("ORDER_DELIVERY_LOCATION_MISSING",
                        "Order " + orderId + " does not have delivery coordinates");
            }

            return new OrderSnapshot(
                    UUID.fromString(response.getOrderId()),
                    response.getStatus(),
                    response.getServiceType(),
                    parseOptionalUuid(response.getCompanyId()),
                    response.getPickupAddress().getLatitude(),
                    response.getPickupAddress().getLongitude(),
                    response.getDeliveryAddress().getLatitude(),
                    response.getDeliveryAddress().getLongitude(),
                    response.hasParcelSize() ? response.getParcelSize() : ParcelSize.SMALL,
                    response.getItemsList().stream().mapToInt(item -> item.getQuantity()).sum(),
                    buildPickupLabel(response),
                    Instant.ofEpochSecond(
                            response.getCreatedAt().getSeconds(),
                            response.getCreatedAt().getNanos()).atOffset(ZoneOffset.UTC));
        } catch (StatusRuntimeException ex) {
            throw mapGrpcError(orderId, ex);
        }
    }

    public void markAssignmentPending(UUID orderId) {
        GatewayPrincipalProvider.GatewayPrincipal principal =
                gatewayPrincipalProvider.requireCurrentPrincipal();
        try {
            UpdateOrderStatusResponse response = authenticatedStub(principal).updateOrderStatus(
                    UpdateOrderStatusRequest.newBuilder()
                            .setOrderId(orderId.toString())
                            .setNewStatus(OrderStatus.ASSIGNMENT_PENDING)
                            .setReason("reassignment-required")
                            .build());

            if (response.hasResponse() && !response.getResponse().getSuccess()) {
                throw new BusinessException(
                        response.getResponse().getError().getCode(),
                        response.getResponse().getError().getMessage());
            }
        } catch (StatusRuntimeException ex) {
            throw mapGrpcError(orderId, ex);
        }
    }

    public List<OrderSnapshot> listOrdersByStatuses(Collection<OrderStatus> statuses, int limitPerStatus) {
        GatewayPrincipalProvider.GatewayPrincipal principal =
                gatewayPrincipalProvider.requireCurrentPrincipal();
        List<OrderSnapshot> results = new ArrayList<>();
        int safeLimit = Math.max(1, Math.min(limitPerStatus, 100));

        for (OrderStatus status : statuses) {
            try {
                ListOrdersResponse response = authenticatedStub(principal).listOrders(
                        ListOrdersRequest.newBuilder()
                                .setPagination(PaginationRequest.newBuilder()
                                        .setPage(1)
                                        .setPageSize(safeLimit)
                                        .build())
                                .setStatus(status)
                                .setSortBy("created_at")
                                .setSortDesc(true)
                                .build());

                if (response.hasResponse() && !response.getResponse().getSuccess()) {
                    String code = response.getResponse().getError().getCode();
                    String message = response.getResponse().getError().getMessage();
                    throw new BusinessException(code != null ? code : "ORDER_SERVICE_ERROR",
                            message != null ? message : "order-service rejected list request");
                }

                response.getOrdersList().forEach(order -> results.add(toSnapshot(order)));
            } catch (StatusRuntimeException ex) {
                throw mapGrpcError(null, ex);
            }
        }

        return results;
    }

    /**
     * Verifies the customer OTP in order-service. Logistics calls this from the
     * assignment endpoint so the assigned-courier authorization remains tied to
     * the assignment lifecycle.
     */
    public OrderStatus verifyDeliveryCode(UUID orderId, String confirmationCode) {
        GatewayPrincipalProvider.GatewayPrincipal principal =
                gatewayPrincipalProvider.requireCurrentPrincipal();

        try {
            VerifyDeliveryCodeResponse response = authenticatedStub(principal).verifyDeliveryCode(
                    VerifyDeliveryCodeRequest.newBuilder()
                            .setOrderId(orderId.toString())
                            .setConfirmationCode(confirmationCode != null ? confirmationCode : "")
                            .build());

            if (response.hasResponse() && !response.getResponse().getSuccess()) {
                String code = response.getResponse().getError().getCode();
                String message = response.getResponse().getError().getMessage();
                throw new BusinessException(code != null ? code : "ORDER_SERVICE_ERROR",
                        message != null ? message : "order-service rejected delivery code");
            }

            return response.getCurrentStatus();
        } catch (StatusRuntimeException ex) {
            throw mapGrpcError(orderId, ex);
        }
    }

    public ResendDeliveryCodeResult resendDeliveryConfirmationCode(UUID orderId) {
        GatewayPrincipalProvider.GatewayPrincipal principal =
                gatewayPrincipalProvider.requireCurrentPrincipal();

        try {
            ResendDeliveryConfirmationCodeResponse response =
                    authenticatedStub(principal).resendDeliveryConfirmationCode(
                            ResendDeliveryConfirmationCodeRequest.newBuilder()
                                    .setOrderId(orderId.toString())
                                    .build());

            if (response.hasResponse() && !response.getResponse().getSuccess()) {
                String code = response.getResponse().getError().getCode();
                String message = response.getResponse().getError().getMessage();
                throw new BusinessException(code != null ? code : "ORDER_SERVICE_ERROR",
                        message != null ? message : "order-service rejected resend request");
            }

            return new ResendDeliveryCodeResult(
                    response.getCurrentStatus(),
                    Instant.ofEpochSecond(
                            response.getCodeExpiresAt().getSeconds(),
                            response.getCodeExpiresAt().getNanos()).atOffset(ZoneOffset.UTC),
                    response.getRegenerated());
        } catch (StatusRuntimeException ex) {
            throw mapGrpcError(orderId, ex);
        }
    }

    private OrderServiceGrpc.OrderServiceBlockingStub authenticatedStub(
            GatewayPrincipalProvider.GatewayPrincipal principal) {
        return orderStub.withInterceptors(
                new AuthForwardingInterceptor(principal.userId(), principal.rolesCsv()));
    }

    private BusinessException mapGrpcError(UUID orderId, StatusRuntimeException ex) {
        Status.Code code = ex.getStatus().getCode();
        String description = ex.getStatus().getDescription();

        log.error("gRPC call to order-service failed orderId={} code={} description={}",
                orderId, code, description);

        String businessCode = switch (code) {
            case NOT_FOUND -> "ORDER_NOT_FOUND";
            case INVALID_ARGUMENT -> "INVALID_ARGUMENT";
            case PERMISSION_DENIED -> "FORBIDDEN";
            case UNAUTHENTICATED -> "UNAUTHENTICATED";
            default -> "ORDER_SERVICE_ERROR";
        };

        return new BusinessException(businessCode,
                description != null ? description : "Failed to load order from order-service");
    }

    private OrderSnapshot toSnapshot(GetOrderResponse response) {
        if (!response.hasPickupAddress()) {
            throw new BusinessException("ORDER_PICKUP_LOCATION_MISSING",
                    "Order " + response.getOrderId() + " does not have pickup coordinates");
        }
        if (!response.hasDeliveryAddress()) {
            throw new BusinessException("ORDER_DELIVERY_LOCATION_MISSING",
                    "Order " + response.getOrderId() + " does not have delivery coordinates");
        }

        return new OrderSnapshot(
                UUID.fromString(response.getOrderId()),
                response.getStatus(),
                response.getServiceType(),
                parseOptionalUuid(response.getCompanyId()),
                response.getPickupAddress().getLatitude(),
                response.getPickupAddress().getLongitude(),
                response.getDeliveryAddress().getLatitude(),
                response.getDeliveryAddress().getLongitude(),
                response.hasParcelSize() ? response.getParcelSize() : ParcelSize.SMALL,
                response.getItemsList().stream().mapToInt(item -> item.getQuantity()).sum(),
                buildPickupLabel(response),
                Instant.ofEpochSecond(
                        response.getCreatedAt().getSeconds(),
                        response.getCreatedAt().getNanos()).atOffset(ZoneOffset.UTC));
    }

    private String buildPickupLabel(GetOrderResponse response) {
        var pickup = response.getPickupAddress();
        return String.format("%s, %s %s", pickup.getCity(), pickup.getStreet(), pickup.getHouse()).trim();
    }

    private UUID parseOptionalUuid(String rawUuid) {
        if (rawUuid == null || rawUuid.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(rawUuid);
        } catch (IllegalArgumentException ex) {
            log.warn("Ignoring malformed companyId from order-service value={}", rawUuid);
            return null;
        }
    }

    /**
     * Minimal order snapshot needed by the auto-assignment workflow.
     */
    public record OrderSnapshot(
            UUID orderId,
            OrderStatus status,
            ServiceType serviceType,
            UUID companyId,
            double pickupLatitude,
            double pickupLongitude,
            double deliveryLatitude,
            double deliveryLongitude,
            ParcelSize parcelSize,
            int itemQuantity,
            String pickupAddressLabel,
            OffsetDateTime createdAt
    ) {}

    public record ResendDeliveryCodeResult(
            OrderStatus status,
            OffsetDateTime expiresAt,
            boolean regenerated
    ) {}
}
