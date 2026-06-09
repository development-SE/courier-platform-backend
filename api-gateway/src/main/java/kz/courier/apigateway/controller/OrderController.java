package kz.courier.apigateway.controller;

import java.time.OffsetDateTime;
import java.util.Map;

import io.jsonwebtoken.ExpiredJwtException;
import kz.courier.apigateway.error.GatewayErrorWriter;
import kz.courier.common.error.StandardErrorResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import kz.courier.apigateway.dto.request.order.CreateOrderRequestDto;
import kz.courier.apigateway.dto.request.order.OrderListFilterDto;
import kz.courier.apigateway.dto.request.order.UpdateOrderStatusRequestDto;
import kz.courier.apigateway.dto.response.ApiResponse;
import kz.courier.apigateway.grpc.AuthContext;
import kz.courier.apigateway.grpc.OrderClient;
import kz.courier.apigateway.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST facade for the order-service gRPC API.
 *
 * <h3>Auth flow</h3>
 * <pre>
 *   HTTP Request
 *      ↓
 *   JwtAuthenticationFilter  (validate JWT — already done by the time we get here)
 *      ↓
 *   OrderController.extractAuth()  — re-parse claims to get userId, roles, token
 *      ↓
 *   AuthContext (immutable record) — safe request-scoped carrier, no ThreadLocal
 *      ↓
 *   OrderClient  — attaches a fresh AuthForwardingInterceptor to the stub per call
 *      ↓
 *   gRPC stub.withInterceptors(new AuthForwardingInterceptor(userId, roles, token))
 *      ↓
 *   gRPC Metadata: Authorization / x-user-id / x-user-roles
 *      ↓
 *   order-service AuthInterceptor reads metadata → populates GrpcAuthContext
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderClient orderClient;
    private final JwtUtil     jwtUtil;
    private final GatewayErrorWriter errorWriter;

    // ── Endpoints ─────────────────────────────────────────────────────────────

    /** POST /api/v1/orders — create a new order */
    @PostMapping
    public ResponseEntity<?> createOrder(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @RequestBody CreateOrderRequestDto request,
            ServerWebExchange exchange) {

        log.info("REST: Create Order request");
        AuthContext auth = extractAuth(authHeader);
        return mapToResponseEntity(orderClient.createOrder(request, auth), HttpStatus.CREATED, exchange);
    }

    /** GET /api/v1/orders/{orderId} — fetch a single order */
    @GetMapping("/{orderId}")
    public ResponseEntity<?> getOrder(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @PathVariable String orderId,
            ServerWebExchange exchange) {

        log.info("REST: Get Order request for ID: {}", orderId);
        AuthContext auth = extractAuth(authHeader);
        return mapToResponseEntity(orderClient.getOrder(orderId, auth), HttpStatus.OK, exchange);
    }

    /** PATCH /api/v1/orders/{orderId}/status — update order status */
    @PatchMapping("/{orderId}/status")
    public ResponseEntity<?> updateOrderStatus(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @PathVariable String orderId,
            @RequestBody UpdateOrderStatusRequestDto request,
            ServerWebExchange exchange) {

        log.info("REST: Update Order Status request for ID: {}", orderId);
        AuthContext auth = extractAuth(authHeader);
        return mapToResponseEntity(orderClient.updateOrderStatus(orderId, request, auth), HttpStatus.OK, exchange);
    }

    /** PUT /api/v1/orders/{orderId}/delivery-address — update order delivery address details */
    @PutMapping("/{orderId}/delivery-address")
    public ResponseEntity<?> updateOrderAddress(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @PathVariable String orderId,
            @RequestBody Map<String, String> request,
            ServerWebExchange exchange) {

        log.info("REST: Update Order Address request for ID: {}", orderId);
        AuthContext auth = extractAuth(authHeader);
        String house = request.get("house");
        String apartment = request.get("apartment");
        String entrance = request.get("entrance");
        String floor = request.get("floor");
        return mapToResponseEntity(
                orderClient.updateOrderAddress(orderId, house, apartment, entrance, floor, auth),
                HttpStatus.OK,
                exchange
        );
    }

    /** GET /api/v1/orders/{orderId}/delivery-confirmation-code - in-app fallback for customer */
    @GetMapping("/{orderId}/delivery-confirmation-code")
    public ResponseEntity<?> getDeliveryConfirmationCode(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @PathVariable String orderId,
            ServerWebExchange exchange) {

        log.info("REST: Get Delivery Confirmation Code request for ID: {}", orderId);
        AuthContext auth = extractAuth(authHeader);
        return mapToResponseEntity(orderClient.getDeliveryConfirmationCode(orderId, auth), HttpStatus.OK, exchange);
    }

    /** GET /api/v1/orders — list orders with optional filters */
    @GetMapping
    public ResponseEntity<?> listOrders(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @RequestParam(required = false)              String companyId,
            @RequestParam(required = false)              String userId,
            @RequestParam(required = false)              String clientId,
            @RequestParam(required = false)              String status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime fromDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime toDate,
            @RequestParam(required = false)              Double minAmount,
            @RequestParam(required = false)              Double maxAmount,
            @RequestParam(defaultValue = "1")            int    page,
            @RequestParam(defaultValue = "10")           int    size,
            @RequestParam(required = false)              String sort,
            @RequestParam(defaultValue = "createdAt")    String sortBy,
            @RequestParam(defaultValue = "true")         boolean sortDesc,
            ServerWebExchange exchange) {

        log.info("REST: List Orders request");
        AuthContext auth = extractAuth(authHeader);
        String[] parsedSort = parseSort(sort, sortBy, sortDesc);
        OrderListFilterDto filter = OrderListFilterDto.builder()
                .companyId(companyId)
                .userId(userId != null ? userId : clientId)
                .status(status)
                .fromDate(fromDate)
                .toDate(toDate)
                .minAmount(minAmount)
                .maxAmount(maxAmount)
                .page(page)
                .size(size)
                .sortBy(parsedSort[0])
                .sortDesc(Boolean.parseBoolean(parsedSort[1]))
                .build();
        return mapToResponseEntity(
                orderClient.listOrders(filter, auth),
                HttpStatus.OK,
                exchange);
    }

    // ── Auth extraction ───────────────────────────────────────────────────────

    /**
     * Parses the already-validated JWT and returns an immutable {@link AuthContext}.
     *
     * <p>The {@code JwtAuthenticationFilter} has already rejected any request with an
     * invalid token, so this is purely a lightweight claim-extraction step — no
     * signature re-verification needed on the happy path.
     *
     * <p>If the header is missing or malformed, a {@code 401} is returned immediately.
     */
    private AuthContext extractAuth(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("[OrderController] Missing or malformed Authorization header");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authorization header required");
        }

        String token = authHeader.substring(7);
        try {
            io.jsonwebtoken.Claims claims = jwtUtil.extractAllClaims(token);
            String userId = claims.getSubject();
            String roles  = claims.get("role", String.class);
            if (roles == null) {
                roles = claims.get("roles", String.class);
            }
            String companyId = claims.get("companyId", String.class);
            log.debug("[OrderController] Auth extracted: userId={} roles={}", userId, roles);
            return AuthContext.of(userId, roles != null ? roles : "", token, companyId);
        } catch (ExpiredJwtException e) {
            log.warn("[OrderController] JWT expired at {} for request token",
                    e.getClaims() != null ? e.getClaims().getExpiration() : "unknown");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT_EXPIRED");
        } catch (Exception e) {
            log.warn("[OrderController] Failed to extract JWT claims");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT_INVALID");
        }
    }

    private String[] parseSort(String sort, String fallbackSortBy, boolean fallbackSortDesc) {
        if (sort == null || sort.isBlank()) {
            return new String[] { fallbackSortBy, String.valueOf(fallbackSortDesc) };
        }

        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();
        boolean desc = parts.length > 1
                ? "desc".equalsIgnoreCase(parts[1].trim())
                : fallbackSortDesc;
        return new String[] { field, String.valueOf(desc) };
    }

    // ── Response mapping ──────────────────────────────────────────────────────

    /**
     * Converts a gRPC-derived {@link ApiResponse} to HTTP, mapping domain error
     * codes to appropriate HTTP status codes.
     */
    private <T> ResponseEntity<?> mapToResponseEntity(
            ApiResponse<T> response,
            HttpStatus successStatus,
            ServerWebExchange exchange) {

        if (response.isSuccess()) {
            return ResponseEntity.status(successStatus).body(response);
        }

        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        if (response.getError() != null && response.getError().getCode() != null) {
            status = switch (response.getError().getCode()) {
                case "ALREADY_EXISTS"                         -> HttpStatus.CONFLICT;
                case "NOT_FOUND", "ORDER_NOT_FOUND"          -> HttpStatus.NOT_FOUND;
                case "INVALID_ARGUMENT", "EMPTY_ITEMS",
                     "TERMINAL_STATUS", "INVALID_UUID",
                     "INVALID_STATUS", "INVALID_STATUS_TRANSITION",
                     "DEDICATED_FLOW_REQUIRED", "INVALID_OTP_FORMAT",
                     "INVALID_OTP", "OTP_EXPIRED",
                     "OTP_ATTEMPTS_EXCEEDED"                 -> HttpStatus.BAD_REQUEST;
                case "OTP_ALREADY_ACTIVE"                    -> HttpStatus.CONFLICT;
                case "OTP_NOT_FOUND"                         -> HttpStatus.NOT_FOUND;
                case "PERMISSION_DENIED", "FORBIDDEN"        -> HttpStatus.FORBIDDEN;
                case "UNAUTHENTICATED", "UNAUTHORIZED"       -> HttpStatus.UNAUTHORIZED;
                default                                       -> HttpStatus.INTERNAL_SERVER_ERROR;
            };
        }

        String code = response.getError() != null && response.getError().getCode() != null
                ? response.getError().getCode()
                : "INTERNAL_ERROR";
        String message = response.getError() != null && response.getError().getMessage() != null
                ? response.getError().getMessage()
                : "Request failed";
        StandardErrorResponse errorBody = errorWriter.body(exchange, status, code, message);
        return ResponseEntity.status(status).body(errorBody);
    }
}
