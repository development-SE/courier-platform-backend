package kz.courier.logisticsservice.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import kz.courier.logisticsservice.exception.BusinessException;
import kz.courier.logisticsservice.security.GatewayPrincipalProvider;
import kz.courier.order.v1.GetOrderRequest;
import kz.courier.order.v1.GetOrderResponse;
import kz.courier.order.v1.OrderServiceGrpc;
import kz.courier.order.v1.OrderStatus;
import kz.courier.order.v1.ResendDeliveryConfirmationCodeRequest;
import kz.courier.order.v1.ResendDeliveryConfirmationCodeResponse;
import kz.courier.order.v1.VerifyDeliveryCodeRequest;
import kz.courier.order.v1.VerifyDeliveryCodeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

            return new OrderSnapshot(
                    UUID.fromString(response.getOrderId()),
                    response.getStatus(),
                    response.getPickupAddress().getLatitude(),
                    response.getPickupAddress().getLongitude(),
                    buildPickupLabel(response));
        } catch (StatusRuntimeException ex) {
            throw mapGrpcError(orderId, ex);
        }
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

    private String buildPickupLabel(GetOrderResponse response) {
        var pickup = response.getPickupAddress();
        return String.format("%s, %s %s", pickup.getCity(), pickup.getStreet(), pickup.getHouse()).trim();
    }

    /**
     * Minimal order snapshot needed by the auto-assignment workflow.
     */
    public record OrderSnapshot(
            UUID orderId,
            OrderStatus status,
            double pickupLatitude,
            double pickupLongitude,
            String pickupAddressLabel
    ) {}

    public record ResendDeliveryCodeResult(
            OrderStatus status,
            OffsetDateTime expiresAt,
            boolean regenerated
    ) {}
}
