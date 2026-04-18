package kz.courier.apigateway.controller;

import kz.courier.apigateway.dto.request.order.CreateOrderRequestDto;
import kz.courier.apigateway.dto.request.order.OrderListFilterDto;
import kz.courier.apigateway.dto.request.order.UpdateOrderStatusRequestDto;
import kz.courier.apigateway.dto.response.ApiResponse;
import kz.courier.apigateway.grpc.AuthContext;
import kz.courier.apigateway.grpc.OrderClient;
import kz.courier.apigateway.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.time.OffsetDateTime;

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

    // ── Endpoints ─────────────────────────────────────────────────────────────

    /** POST /api/v1/orders — create a new order */
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> createOrder(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @RequestBody CreateOrderRequestDto request) {

        log.info("REST: Create Order request");
        AuthContext auth = extractAuth(authHeader);
        return mapToResponseEntity(orderClient.createOrder(request, auth), HttpStatus.CREATED);
    }

    /** GET /api/v1/orders/{orderId} — fetch a single order */
    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getOrder(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @PathVariable String orderId) {

        log.info("REST: Get Order request for ID: {}", orderId);
        AuthContext auth = extractAuth(authHeader);
        return mapToResponseEntity(orderClient.getOrder(orderId, auth), HttpStatus.OK);
    }

    /** PATCH /api/v1/orders/{orderId}/status — update order status */
    @PatchMapping("/{orderId}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateOrderStatus(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @PathVariable String orderId,
            @RequestBody UpdateOrderStatusRequestDto request) {

        log.info("REST: Update Order Status request for ID: {}", orderId);
        AuthContext auth = extractAuth(authHeader);
        return mapToResponseEntity(orderClient.updateOrderStatus(orderId, request, auth), HttpStatus.OK);
    }

    /** GET /api/v1/orders/{orderId}/delivery-confirmation-code - in-app fallback for customer */
    @GetMapping("/{orderId}/delivery-confirmation-code")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDeliveryConfirmationCode(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @PathVariable String orderId) {

        log.info("REST: Get Delivery Confirmation Code request for ID: {}", orderId);
        AuthContext auth = extractAuth(authHeader);
        return mapToResponseEntity(orderClient.getDeliveryConfirmationCode(orderId, auth), HttpStatus.OK);
    }

    /** GET /api/v1/orders — list orders with optional filters */
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> listOrders(
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
            @RequestParam(defaultValue = "true")         boolean sortDesc) {

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
                HttpStatus.OK);
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
        } catch (Exception e) {
            log.warn("[OrderController] Failed to extract JWT claims: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired token");
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
    private <T> ResponseEntity<ApiResponse<T>> mapToResponseEntity(
            ApiResponse<T> response, HttpStatus successStatus) {

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

        return ResponseEntity.status(status).body(response);
    }
}
