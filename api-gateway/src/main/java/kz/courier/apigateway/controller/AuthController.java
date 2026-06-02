package kz.courier.apigateway.controller;

import jakarta.validation.Valid;
import kz.courier.apigateway.dto.request.CreateStaffUserRequest;
import kz.courier.apigateway.dto.request.LoginRequest;
import kz.courier.apigateway.dto.request.LogoutAllRequest;
import kz.courier.apigateway.dto.request.RefreshTokenRequest;
import kz.courier.apigateway.dto.request.RegisterRequest;
import kz.courier.apigateway.dto.response.*;
import kz.courier.apigateway.grpc.AuthClient;
import kz.courier.apigateway.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthClient authGrpcClient;
    private final JwtUtil jwtUtil;

    /**
     * POST /api/v1/auth/register
     * Register a new user
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(
            @Valid @RequestBody RegisterRequest request) {

        log.info("REST: Register request for email: {}", request.getEmail());

        ApiResponse<RegisterResponse> response = authGrpcClient.register(request);

        HttpStatus status = response.isSuccess() ? HttpStatus.CREATED : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * POST /api/v1/auth/staff
     * Super-admin-only platform staff creation for ADMIN accounts.
     */
    @PostMapping("/staff")
    public ResponseEntity<ApiResponse<RegisterResponse>> createStaffUser(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody CreateStaffUserRequest request) {

        AuthActor actor = requireSuperAdminActor(authorization);
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("FORBIDDEN", "Only SUPER_ADMIN can create admin users"));
        }

        ApiResponse<RegisterResponse> response =
                authGrpcClient.createStaffUser(request, actor.userId(), actor.role());

        HttpStatus status = response.isSuccess() ? HttpStatus.CREATED : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * POST /api/v1/auth/login
     * User login
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request) {

        log.info("REST: Login request for email: {}", request.getEmail());

        ApiResponse<LoginResponse> response = authGrpcClient.login(request);

        HttpStatus status = response.isSuccess() ? HttpStatus.OK : HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * POST /api/v1/auth/refresh
     * Refresh access token
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshTokenResponse>> refreshToken(
            @Valid @RequestBody RefreshTokenRequest request) {

        log.info("REST: Refresh token request");

        ApiResponse<RefreshTokenResponse> response = authGrpcClient.refreshToken(request);

        HttpStatus status = response.isSuccess() ? HttpStatus.OK : HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(status).body(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<String>> logout(
            @Valid @RequestBody RefreshTokenRequest request) {

        log.info("REST: Logout request");

        ApiResponse<String> response = authGrpcClient.logout(request);

        HttpStatus status = response.isSuccess() ? HttpStatus.OK : HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(status).body(response);
    }

    @PostMapping("/logout-all")
    public ResponseEntity<ApiResponse<String>> logoutAll(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) LogoutAllRequest request) {

        AuthActor actor = requireAuthenticatedActor(authorization);
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Valid Authorization header required"));
        }

        String targetUserId = request != null ? request.getTargetUserId() : null;
        ApiResponse<String> response = authGrpcClient.logoutAll(actor.userId(), actor.role(), targetUserId);

        HttpStatus status = response.isSuccess() ? HttpStatus.OK : mapAuthStatus(response);
        return ResponseEntity.status(status).body(response);
    }

    /**
     * GET /api/v1/auth/verify?token=xxx
     * Verify email with confirmation token
     */
    @GetMapping("/verify")
    public ResponseEntity<ApiResponse<String>> verifyEmail(
            @RequestParam String token) {

        log.info("REST: Verify email request");

        ApiResponse<String> response = authGrpcClient.verifyEmail(token);

        HttpStatus status = response.isSuccess() ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * GET /api/v1/auth/users?page=1&size=10&role=COURIER
     * List auth users for admin views.
     */
    @GetMapping("/users")
    public ResponseEntity<ApiResponse<List<UserResponse>>> listUsers(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "ADMIN") String role) {

        AuthActor actor = requirePrivilegedActor(authorization);
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("FORBIDDEN", "Only privileged users can view users"));
        }

        String filterRole = role == null || role.isBlank() ? "ADMIN" : role.trim().toUpperCase();
        if (!List.of("ADMIN", "COURIER").contains(filterRole)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("INVALID_ROLE", "Only ADMIN or COURIER users can be listed here"));
        }
        if ("ADMIN".equals(filterRole) && !"SUPER_ADMIN".equals(actor.role())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("FORBIDDEN", "Only SUPER_ADMIN can view admin users"));
        }

        log.info("REST: List auth users request - page: {}, size: {}, role: {}", page, size, filterRole);

        ApiResponse<List<UserResponse>> response = authGrpcClient.listUsers(page, size, filterRole);

        HttpStatus status = response.isSuccess() ? HttpStatus.OK : HttpStatus.FORBIDDEN;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * DELETE /api/v1/auth/users/{id}
     * Delete an admin user (SuperAdmin only)
     */
    @DeleteMapping("/users/{id}")
    public ResponseEntity<ApiResponse<String>> deleteUser(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id) {

        AuthActor actor = requireSuperAdminActor(authorization);
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("FORBIDDEN", "Only SUPER_ADMIN can delete admin users"));
        }

        log.info("REST: Delete auth user request for userId: {}", id);

        ApiResponse<String> response = authGrpcClient.deleteUser(id);

        HttpStatus status = response.isSuccess() ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(response);
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

        return new AuthActor(claims.getSubject(), role);
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

    private record AuthActor(String userId, String role) {
    }
}
