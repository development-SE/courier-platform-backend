package kz.courier.apigateway.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import jakarta.validation.Valid;
import kz.courier.apigateway.dto.request.ChangePasswordRequestDto;
import kz.courier.apigateway.dto.request.CreateStaffUserRequest;
import kz.courier.apigateway.dto.request.LoginRequest;
import kz.courier.apigateway.dto.request.LogoutAllRequest;
import kz.courier.apigateway.dto.request.RefreshTokenRequest;
import kz.courier.apigateway.dto.request.RegisterRequest;
import kz.courier.apigateway.dto.request.UpdateProfileRequest;
import kz.courier.apigateway.dto.response.ApiResponse;
import kz.courier.apigateway.dto.response.LoginResponse;
import kz.courier.apigateway.dto.response.RefreshTokenResponse;
import kz.courier.apigateway.dto.response.RegisterResponse;
import kz.courier.apigateway.dto.response.UserResponse;
import kz.courier.apigateway.error.GatewayErrorWriter;
import kz.courier.apigateway.grpc.AuthClient;
import kz.courier.apigateway.observability.CorrelationIdFilter;
import kz.courier.apigateway.security.JwtUtil;
import kz.courier.common.error.StandardErrorResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
     * Admin-only user creation: SUPER_ADMIN can create ADMIN accounts, ADMIN/SUPER_ADMIN can create COURIER employee accounts.
     */
    @PostMapping("/staff")
    public ResponseEntity<?> createStaffUser(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody CreateStaffUserRequest request,
            ServerWebExchange exchange) {

        AuthActor actor = requireAdminOrSuperAdminActor(authorization);
        if (actor == null) {
            return error(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "Only ADMIN or SUPER_ADMIN can create staff accounts");
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
     * GET /api/v1/auth/users?page=1&size=10&role=COURIER|CLIENT|ADMIN
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
        if (!List.of("ADMIN", "COURIER", "CLIENT").contains(filterRole)) {
            return error(exchange, HttpStatus.BAD_REQUEST, "INVALID_ROLE",
                    "Only ADMIN, COURIER or CLIENT users can be listed here");
        }
        if ("ADMIN".equals(filterRole) && !"SUPER_ADMIN".equals(actor.role())) {
            return error(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "Only SUPER_ADMIN can view admin users");
        }

        log.info("REST: List auth users request - page: {}, size: {}, role: {}", page, size, filterRole);

        ApiResponse<List<UserResponse>> response = authGrpcClient.listUsers(page, size, filterRole);

        return apiResponse(exchange, response, HttpStatus.OK, HttpStatus.BAD_REQUEST);
    }

    /**
     * GET /api/v1/auth/profile
     * Returns the authenticated user's own profile.
     */
    @GetMapping("/profile")
    public ResponseEntity<?> getProfile(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            ServerWebExchange exchange) {

        AuthActor actor = requireAuthenticatedActor(authorization);
        if (actor == null) {
            return error(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Valid Authorization header required");
        }

        ApiResponse<UserResponse> response = authGrpcClient.getProfile(actor.userId());
        return apiResponse(exchange, response, HttpStatus.OK, HttpStatus.NOT_FOUND);
    }

    /**
     * PUT /api/v1/auth/profile
     * Updates the authenticated user's own profile (firstName, lastName, email, phone).
     */
    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody UpdateProfileRequest request,
            ServerWebExchange exchange) {

        AuthActor actor = requireAuthenticatedActor(authorization);
        if (actor == null) {
            return error(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Valid Authorization header required");
        }

        ApiResponse<String> response = authGrpcClient.updateProfile(actor.userId(), request);
        return apiResponse(exchange, response, HttpStatus.OK, HttpStatus.BAD_REQUEST);
    }

    /**
     * POST /api/v1/auth/change-password
     * Changes the authenticated user's password.
     */
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody ChangePasswordRequestDto request,
            ServerWebExchange exchange) {

        AuthActor actor = requireAuthenticatedActor(authorization);
        if (actor == null) {
            return error(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Valid Authorization header required");
        }

        ApiResponse<String> response = authGrpcClient.changePassword(actor.userId(), request.getOldPassword(), request.getNewPassword());
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

    private AuthActor requireAdminOrSuperAdminActor(String authorization) {
        AuthActor actor = requireAuthenticatedActor(authorization);
        if (actor == null) return null;
        return List.of("ADMIN", "SUPER_ADMIN").contains(actor.role()) ? actor : null;
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
