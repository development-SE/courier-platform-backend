package kz.courier.apigateway.controller;

import jakarta.validation.Valid;
import kz.courier.apigateway.dto.request.CreateStaffUserRequest;
import kz.courier.apigateway.dto.request.LoginRequest;
import kz.courier.apigateway.dto.request.LogoutAllRequest;
import kz.courier.apigateway.dto.request.RefreshTokenRequest;
import kz.courier.apigateway.dto.request.RegisterRequest;
import kz.courier.apigateway.dto.request.UpdateProfileRequest;
import kz.courier.apigateway.dto.response.*;
import kz.courier.apigateway.error.GatewayErrorWriter;
import kz.courier.apigateway.grpc.AuthClient;
import kz.courier.apigateway.observability.CorrelationIdFilter;
import kz.courier.apigateway.security.JwtUtil;
import kz.courier.common.error.StandardErrorResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthClient authGrpcClient;
    private final JwtUtil jwtUtil;
    private final GatewayErrorWriter errorWriter;

    /**
     * POST /api/v1/auth/register
     * Register a new user
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(
            @Valid @RequestBody RegisterRequest request,
            ServerWebExchange exchange) {

        log.info("REST: Register request for email: {}", request.getEmail());

        ApiResponse<RegisterResponse> response = authGrpcClient.register(request);

        return apiResponse(exchange, response, HttpStatus.CREATED, HttpStatus.BAD_REQUEST);
    }

    /**
     * POST /api/v1/auth/staff
     * Super-admin-only platform staff creation for ADMIN accounts.
     */
    @PostMapping("/staff")
    public ResponseEntity<?> createStaffUser(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody CreateStaffUserRequest request,
            ServerWebExchange exchange) {

        AuthActor actor = requireSuperAdminActor(authorization);
        if (actor == null) {
            return error(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "Only SUPER_ADMIN can create admin users");
        }

        ApiResponse<RegisterResponse> response = authGrpcClient.createStaffUser(request, actor.userId(), actor.role());

        return apiResponse(exchange, response, HttpStatus.CREATED, HttpStatus.BAD_REQUEST);
    }

    /**
     * POST /api/v1/auth/login
     * User login
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request,
            ServerWebExchange exchange) {

        log.info("REST: Login request for email: {}", request.getEmail());

        ApiResponse<LoginResponse> response = authGrpcClient.login(request);

        return apiResponse(exchange, response, HttpStatus.OK, HttpStatus.UNAUTHORIZED);
    }

    /**
     * POST /api/v1/auth/refresh
     * Refresh access token
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(
            @Valid @RequestBody RefreshTokenRequest request,
            ServerWebExchange exchange) {

        log.info("REST: Refresh token request");

        ApiResponse<RefreshTokenResponse> response = authGrpcClient.refreshToken(request);

        return apiResponse(exchange, response, HttpStatus.OK, HttpStatus.UNAUTHORIZED);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @Valid @RequestBody RefreshTokenRequest request,
            ServerWebExchange exchange) {

        log.info("REST: Logout request");

        ApiResponse<String> response = authGrpcClient.logout(request);

        return apiResponse(exchange, response, HttpStatus.OK, HttpStatus.UNAUTHORIZED);
    }

    @PostMapping("/logout-all")
    public ResponseEntity<?> logoutAll(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) LogoutAllRequest request,
            ServerWebExchange exchange) {

        AuthActor actor = requireAuthenticatedActor(authorization);
        if (actor == null) {
            return error(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Valid Authorization header required");
        }

        String targetUserId = request != null ? request.getTargetUserId() : null;
        ApiResponse<String> response = authGrpcClient.logoutAll(actor.userId(), actor.role(), targetUserId);

        return apiResponse(exchange, response, HttpStatus.OK, mapAuthStatus(response));
    }

    /**
     * GET /api/v1/auth/verify?token=xxx
     * Verify email with confirmation token
     */
    @GetMapping("/verify")
    public ResponseEntity<?> verifyEmail(
            @RequestParam String token,
            ServerWebExchange exchange) {

        log.info("REST: Verify email request");

        ApiResponse<String> response = authGrpcClient.verifyEmail(token);

        return apiResponse(exchange, response, HttpStatus.OK, HttpStatus.BAD_REQUEST);
    }

    /**
     * GET /api/v1/auth/profile
     * Get the authenticated user's own profile
     */
    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<UserResponse>> getProfile(
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        AuthActor actor = requireAuthenticatedActor(authorization);
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Valid token required"));
        }

        log.info("REST: Get profile for userId: {}", actor.userId());

        ApiResponse<UserResponse> response = authGrpcClient.getProfile(actor.userId());

        HttpStatus status = response.isSuccess() ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * PUT /api/v1/auth/profile
     * Update the authenticated user's own profile (firstName, lastName, email,
     * phone)
     */
    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<String>> updateProfile(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody UpdateProfileRequest request) {

        AuthActor actor = requireAuthenticatedActor(authorization);
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Valid token required"));
        }

        log.info("REST: Update profile for userId: {}", actor.userId());

        ApiResponse<String> response = authGrpcClient.updateProfile(actor.userId(), request);

        HttpStatus status = response.isSuccess() ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * GET /api/v1/auth/users?page=1&size=10&role=COURIER
     * List auth users for admin views.
     */
    @GetMapping("/users")
    public ResponseEntity<?> listUsers(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "ADMIN") String role,
            ServerWebExchange exchange) {

        AuthActor actor = requirePrivilegedActor(authorization);
        if (actor == null) {
            return error(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "Only privileged users can view users");
        }
        String filterRole = role == null || role.isBlank() ? "ADMIN" : role.trim().toUpperCase();
        if (!List.of("ADMIN", "COURIER", "CLIENT", "PARTNER", "DIRECTOR", "MANAGER", "SUPER_ADMIN").contains(filterRole)) {
            return error(exchange, HttpStatus.BAD_REQUEST, "INVALID_ROLE",
                    "Invalid role specified for listing");
        }
        if ("ADMIN".equals(filterRole) && !"SUPER_ADMIN".equals(actor.role())) {
            return error(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "Only SUPER_ADMIN can view admin users");
        }

        log.info("REST: List auth users request - page: {}, size: {}, role: {}", page, size, filterRole);

        ApiResponse<List<UserResponse>> response = authGrpcClient.listUsers(page, size, filterRole);

        return apiResponse(exchange, response, HttpStatus.OK, HttpStatus.BAD_REQUEST);
    }

    /**
     * DELETE /api/v1/auth/users/{id}
     * Delete an auth user (ADMIN or SUPER_ADMIN required)
     */
    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id,
            ServerWebExchange exchange) {

        AuthActor actor = requireAuthenticatedActor(authorization);
        if (actor == null || (!"SUPER_ADMIN".equals(actor.role()) && !"ADMIN".equals(actor.role()))) {
            return error(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "Only ADMIN or SUPER_ADMIN can delete users");
        }

        log.info("REST: Delete auth user request for userId: {}", id);

        ApiResponse<String> response = authGrpcClient.deleteUser(id);

        return apiResponse(exchange, response, HttpStatus.OK, HttpStatus.BAD_REQUEST);
    }

    private AuthActor requireAuthenticatedActor(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }

        String token = authorization.substring(7);
        if (!jwtUtil.validateToken(token)) {
            return null;
        }

        var claims = jwtUtil.extractAllClaims(token);
        String role = claims.get("role", String.class);

        return new AuthActor(claims.getSubject(), role != null ? role : "CLIENT");
    }

    private AuthActor requireSuperAdminActor(String authorization) {
        AuthActor actor = requirePrivilegedActor(authorization);
        return actor != null && "SUPER_ADMIN".equals(actor.role()) ? actor : null;
    }

    private AuthActor requirePrivilegedActor(String authorization) {
        AuthActor actor = requireAuthenticatedActor(authorization);
        if (actor == null || !List.of("ADMIN", "SUPER_ADMIN", "DIRECTOR", "MANAGER").contains(actor.role())) {
            return null;
        }
        return actor;
    }



    private HttpStatus mapAuthStatus(ApiResponse<?> response) {
        if (response.getError() == null || response.getError().getCode() == null) {
            return HttpStatus.BAD_REQUEST;
        }
        return switch (response.getError().getCode()) {
            case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "UNAUTHORIZED", "INVALID_REFRESH" -> HttpStatus.UNAUTHORIZED;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private ResponseEntity<?> apiResponse(
            ServerWebExchange exchange,
            ApiResponse<?> response,
            HttpStatus successStatus,
            HttpStatus failureStatus) {
        if (response.isSuccess()) {
            return ResponseEntity.status(successStatus).body(response);
        }

        String code = response.getError() != null && response.getError().getCode() != null
                ? response.getError().getCode()
                : "REQUEST_FAILED";
        String message = response.getError() != null && response.getError().getMessage() != null
                ? response.getError().getMessage()
                : "Request failed";
        return error(exchange, failureStatus, code, message);
    }

    private ResponseEntity<StandardErrorResponse> error(
            ServerWebExchange exchange,
            HttpStatus status,
            String code,
            String message) {
        StandardErrorResponse body = errorWriter.body(exchange, status, code, message);
        return ResponseEntity.status(status)
                .header(CorrelationIdFilter.HEADER, body.traceId())
                .body(body);
    }

    private record AuthActor(String userId, String role) {
    }
}
