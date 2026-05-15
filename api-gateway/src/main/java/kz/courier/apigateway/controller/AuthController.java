package kz.courier.apigateway.controller;

import jakarta.validation.Valid;
import kz.courier.apigateway.dto.request.CreateStaffUserRequest;
import kz.courier.apigateway.dto.request.LoginRequest;
import kz.courier.apigateway.dto.request.RefreshTokenRequest;
import kz.courier.apigateway.dto.request.RegisterRequest;
import kz.courier.apigateway.dto.request.UpdateProfileRequest;
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

        ApiResponse<RegisterResponse> response = authGrpcClient.createStaffUser(request, actor.userId(), actor.role());

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
     * GET /api/v1/auth/users?page=1&size=10&role=ADMIN
     * List auth users by role
     */
    @GetMapping("/users")
    public ResponseEntity<ApiResponse<List<UserResponse>>> listUsers(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "ADMIN") String role) {

        String filterRole = role == null || role.isBlank() ? "ADMIN" : role.trim().toUpperCase();

        // ADMIN role listing requires SUPER_ADMIN access
        if ("ADMIN".equals(filterRole)) {
            AuthActor actor = requireSuperAdminActor(authorization);
            if (actor == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ApiResponse.error("FORBIDDEN", "Only SUPER_ADMIN can view admin users"));
            }
        } else {
            // CLIENT, COURIER etc. require any authenticated actor (e.g. ADMIN)
            AuthActor actor = requireAuthenticatedActor(authorization);
            if (actor == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiResponse.error("UNAUTHORIZED", "Valid token required"));
            }
        }

        log.info("REST: List auth users request - page: {}, size: {}, role: {}", page, size, filterRole);

        ApiResponse<List<UserResponse>> response = authGrpcClient.listUsers(page, size, filterRole);

        HttpStatus status = response.isSuccess() ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * DELETE /api/v1/auth/users/{id}
     * Delete an auth user (ADMIN or SUPER_ADMIN required)
     */
    @DeleteMapping("/users/{id}")
    public ResponseEntity<ApiResponse<String>> deleteUser(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id) {

        AuthActor actor = requireAuthenticatedActor(authorization);
        if (actor == null || (!"SUPER_ADMIN".equals(actor.role()) && !"ADMIN".equals(actor.role()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("FORBIDDEN", "Only ADMIN or SUPER_ADMIN can delete users"));
        }

        log.info("REST: Delete auth user request for userId: {}", id);

        ApiResponse<String> response = authGrpcClient.deleteUser(id);

        HttpStatus status = response.isSuccess() ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(response);
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
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }

        String token = authorization.substring(7);
        if (!jwtUtil.validateToken(token)) {
            return null;
        }

        var claims = jwtUtil.extractAllClaims(token);
        String role = claims.get("role", String.class);
        if (!"SUPER_ADMIN".equals(role)) {
            return null;
        }

        return new AuthActor(claims.getSubject(), role);
    }

    private record AuthActor(String userId, String role) {
    }
}
