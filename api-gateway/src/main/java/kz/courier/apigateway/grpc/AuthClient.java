package kz.courier.apigateway.grpc;

import io.grpc.StatusRuntimeException;
import kz.courier.apigateway.dto.response.ApiResponse;
import kz.courier.apigateway.dto.response.LoginResponse;
import kz.courier.apigateway.dto.response.RefreshTokenResponse;
import kz.courier.apigateway.dto.response.RegisterResponse;
import kz.courier.auth.v1.*;
import kz.courier.common.v1.PaginationRequest;
import kz.courier.apigateway.dto.request.CreateStaffUserRequest;
import kz.courier.apigateway.dto.request.LoginRequest;
import kz.courier.apigateway.dto.request.RefreshTokenRequest;
import kz.courier.apigateway.dto.request.RegisterRequest;
import kz.courier.apigateway.dto.response.*;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AuthClient {

    @GrpcClient("auth-service")
    private AuthServiceGrpc.AuthServiceBlockingStub authStub;

    /**
     * Register new user
     */
    public ApiResponse<RegisterResponse> register(RegisterRequest request) {
        try {
            log.info("gRPC Register request for email: {}", request.getEmail());

            Role publicRole = resolvePublicRegistrationRole(request.getRole());

            kz.courier.auth.v1.RegisterRequest grpcRequest = kz.courier.auth.v1.RegisterRequest.newBuilder()
                    .setEmail(request.getEmail())
                    .setPassword(request.getPassword())
                    .setPhone(request.getPhone() != null ? request.getPhone() : "")
                    .setFirstName(request.getFirstName())
                    .setLastName(request.getLastName())
                    .setPushConsent(request.getPushConsent() != null ? request.getPushConsent() : false)
                    .setRole(publicRole)
                    .build();


            kz.courier.auth.v1.RegisterResponse grpcResponse = authStub.register(grpcRequest);

            if (!grpcResponse.getResponse().getSuccess()) {
                log.warn("Registration failed: {}", grpcResponse.getResponse().getError().getMessage());
                return ApiResponse.error(
                        grpcResponse.getResponse().getError().getCode(),
                        grpcResponse.getResponse().getError().getMessage()
                );
            }

            RegisterResponse response = RegisterResponse.builder()
                    .userId(grpcResponse.getUserId())
                    .message("Registration successful. Please check your email to verify your account.")
                    .build();

            log.info("User registered successfully: {}", grpcResponse.getUserId());
            return ApiResponse.success(response);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid public registration role: {}", request.getRole());
            return ApiResponse.error("INVALID_ROLE", e.getMessage());
        } catch (StatusRuntimeException e) {
            log.error("gRPC error during registration: {}", e.getStatus());
            return ApiResponse.error("GRPC_ERROR", "Service temporarily unavailable: " + e.getStatus().getDescription());
        } catch (Exception e) {
            log.error("Unexpected error during registration", e);
            return ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred");
        }
    }

    private Role resolvePublicRegistrationRole(String requestedRole) {
        if (requestedRole == null || requestedRole.isBlank()) {
            return Role.CLIENT;
        }

        Role role = Role.valueOf(requestedRole.trim().toUpperCase());
        if (role == Role.CLIENT || role == Role.COURIER) {
            return role;
        }

        throw new IllegalArgumentException("Public registration only supports CLIENT or COURIER");
    }

    /**
     * Admin-only platform staff creation.
     */
    public ApiResponse<RegisterResponse> createStaffUser(
            CreateStaffUserRequest request,
            String actorUserId,
            String actorRole
    ) {
        try {
            log.info("gRPC CreateStaffUser request for email: {} role: {}", request.getEmail(), request.getRole());

            Role grpcRole = Role.valueOf(request.getRole().toUpperCase());
            Role grpcActorRole = Role.valueOf(actorRole.toUpperCase());

            kz.courier.auth.v1.CreateStaffUserRequest.Builder grpcRequest =
                    kz.courier.auth.v1.CreateStaffUserRequest.newBuilder()
                            .setEmail(request.getEmail())
                            .setPassword(request.getPassword())
                            .setPhone(request.getPhone() != null ? request.getPhone() : "")
                            .setFirstName(request.getFirstName())
                            .setLastName(request.getLastName())
                            .setPushConsent(request.getPushConsent() != null ? request.getPushConsent() : false)
                            .setRole(grpcRole)
                            .setActorUserId(actorUserId)
                            .setActorRole(grpcActorRole);

            if (request.getCompanyId() != null) {
                grpcRequest.setCompanyId(request.getCompanyId().toString());
            }

            kz.courier.auth.v1.RegisterResponse grpcResponse = authStub.createStaffUser(grpcRequest.build());

            if (!grpcResponse.getResponse().getSuccess()) {
                return ApiResponse.error(
                        grpcResponse.getResponse().getError().getCode(),
                        grpcResponse.getResponse().getError().getMessage()
                );
            }

            RegisterResponse response = RegisterResponse.builder()
                    .userId(grpcResponse.getUserId())
                    .message("Admin user created. Please ask them to verify their email.")
                    .build();

            return ApiResponse.success(response);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("INVALID_ROLE", e.getMessage());
        } catch (StatusRuntimeException e) {
            log.error("gRPC error during staff user creation: {}", e.getStatus());
            return ApiResponse.error("GRPC_ERROR", "Service temporarily unavailable: " + e.getStatus().getDescription());
        } catch (Exception e) {
            log.error("Unexpected error during staff user creation", e);
            return ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred");
        }
    }

    /**
     * User login
     */
    public ApiResponse<LoginResponse> login(LoginRequest request) {
        try {
            log.info("gRPC Login request for email: {}", request.getEmail());

            kz.courier.auth.v1.LoginRequest grpcRequest = kz.courier.auth.v1.LoginRequest.newBuilder()
                    .setEmail(request.getEmail())
                    .setPassword(request.getPassword())
                    .setDeviceId(request.getDeviceId() != null ? request.getDeviceId() : "")
                    .build();

            kz.courier.auth.v1.LoginResponse grpcResponse = authStub.login(grpcRequest);

            if (!grpcResponse.getResponse().getSuccess()) {
                log.warn("Login failed: {}", grpcResponse.getResponse().getError().getMessage());
                return ApiResponse.error(
                        grpcResponse.getResponse().getError().getCode(),
                        grpcResponse.getResponse().getError().getMessage()
                );
            }

            LoginResponse response = LoginResponse.builder()
                    .accessToken(grpcResponse.getAccessToken())
                    .refreshToken(grpcResponse.getRefreshToken())
                    .expiresAt(grpcResponse.getExpiresAt().getSeconds())
                    .role(grpcResponse.getRole().name())
                    .build();

            log.info("User logged in successfully: {}", request.getEmail());
            return ApiResponse.success(response);

        } catch (StatusRuntimeException e) {
            log.error("gRPC error during login: {}", e.getStatus());
            return ApiResponse.error("GRPC_ERROR", "Service temporarily unavailable: " + e.getStatus().getDescription());
        } catch (Exception e) {
            log.error("Unexpected error during login", e);
            return ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred");
        }
    }

    /**
     * Refresh access token
     */
    public ApiResponse<RefreshTokenResponse> refreshToken(RefreshTokenRequest request) {
        try {
            log.info("gRPC Refresh token request");

            kz.courier.auth.v1.RefreshTokenRequest grpcRequest = kz.courier.auth.v1.RefreshTokenRequest.newBuilder()
                    .setRefreshToken(request.getRefreshToken())
                    .build();

            kz.courier.auth.v1.RefreshTokenResponse grpcResponse = authStub.refreshToken(grpcRequest);

            if (!grpcResponse.getResponse().getSuccess()) {
                log.warn("Token refresh failed: {}", grpcResponse.getResponse().getError().getMessage());
                return ApiResponse.error(
                        grpcResponse.getResponse().getError().getCode(),
                        grpcResponse.getResponse().getError().getMessage()
                );
            }

            RefreshTokenResponse response = RefreshTokenResponse.builder()
                    .accessToken(grpcResponse.getAccessToken())
                    .refreshToken(grpcResponse.getRefreshToken())
                    .expiresAt(grpcResponse.getExpiresAt().getSeconds())
                    .build();

            log.info("Token refreshed successfully");
            return ApiResponse.success(response);

        } catch (StatusRuntimeException e) {
            log.error("gRPC error during token refresh: {}", e.getStatus());
            return ApiResponse.error("GRPC_ERROR", "Service temporarily unavailable: " + e.getStatus().getDescription());
        } catch (Exception e) {
            log.error("Unexpected error during token refresh", e);
            return ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred");
        }
    }

    public ApiResponse<String> logout(RefreshTokenRequest request) {
        try {
            log.info("gRPC Logout request");

            kz.courier.common.v1.Response grpcResponse = authStub.logout(
                    LogoutRequest.newBuilder()
                            .setRefreshToken(request.getRefreshToken())
                            .build());

            if (!grpcResponse.getSuccess()) {
                return ApiResponse.error(
                        grpcResponse.getError().getCode(),
                        grpcResponse.getError().getMessage()
                );
            }

            return ApiResponse.success("Logged out successfully");
        } catch (StatusRuntimeException e) {
            log.error("gRPC error during logout: {}", e.getStatus());
            return ApiResponse.error("GRPC_ERROR", "Service temporarily unavailable: " + e.getStatus().getDescription());
        } catch (Exception e) {
            log.error("Unexpected error during logout", e);
            return ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred");
        }
    }

    public ApiResponse<String> logoutAll(String actorUserId, String actorRole, String targetUserId) {
        try {
            log.info("gRPC LogoutAll request for actorUserId={}", actorUserId);

            LogoutAllRequest.Builder grpcRequest = LogoutAllRequest.newBuilder()
                    .setActorUserId(actorUserId)
                    .setActorRole(Role.valueOf(actorRole.toUpperCase()));
            if (targetUserId != null && !targetUserId.isBlank()) {
                grpcRequest.setTargetUserId(targetUserId);
            }

            kz.courier.common.v1.Response grpcResponse = authStub.logoutAll(grpcRequest.build());

            if (!grpcResponse.getSuccess()) {
                return ApiResponse.error(
                        grpcResponse.getError().getCode(),
                        grpcResponse.getError().getMessage()
                );
            }

            return ApiResponse.success("Sessions revoked successfully");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("INVALID_ROLE", "Invalid actor role");
        } catch (StatusRuntimeException e) {
            log.error("gRPC error during logout-all: {}", e.getStatus());
            return ApiResponse.error("GRPC_ERROR", "Service temporarily unavailable: " + e.getStatus().getDescription());
        } catch (Exception e) {
            log.error("Unexpected error during logout-all", e);
            return ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred");
        }
    }

    /**
     * Verify email with token
     */
    public ApiResponse<String> verifyEmail(String token) {
        try {
            log.info("gRPC Verify email request");

            VerifyEmailRequest grpcRequest = VerifyEmailRequest.newBuilder()
                    .setToken(token)
                    .build();

            kz.courier.common.v1.Response grpcResponse = authStub.verifyEmail(grpcRequest);

            if (!grpcResponse.getSuccess()) {
                log.warn("Email verification failed: {}", grpcResponse.getError().getMessage());
                return ApiResponse.error(
                        grpcResponse.getError().getCode(),
                        grpcResponse.getError().getMessage()
                );
            }

            log.info("Email verified successfully");
            return ApiResponse.success("Email verified successfully");

        } catch (StatusRuntimeException e) {
            log.error("gRPC error during email verification: {}", e.getStatus());
            return ApiResponse.error("GRPC_ERROR", "Service temporarily unavailable: " + e.getStatus().getDescription());
        } catch (Exception e) {
            log.error("Unexpected error during email verification", e);
            return ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred");
        }
    }

    /**
     * List users (Admin only)
     */
    public ApiResponse<List<UserResponse>> listUsers(int page, int pageSize) {
        return listUsers(page, pageSize, null);
    }

    public ApiResponse<List<UserResponse>> listUsers(int page, int pageSize, String filterRole) {
        try {
            log.info("gRPC List users request - page: {}, size: {}, role: {}", page, pageSize, filterRole);

            ListUsersRequest.Builder grpcRequest = ListUsersRequest.newBuilder()
                    .setPagination(PaginationRequest.newBuilder()
                            .setPage(page)
                            .setPageSize(pageSize)
                            .setSortBy("created_at")
                            .setAscending(false)
                            .build());

            if (filterRole != null && !filterRole.isBlank()) {
                grpcRequest.setFilterRole(filterRole.trim().toUpperCase());
            }

            ListUsersResponse grpcResponse = authStub.listUsers(grpcRequest.build());

            if (!grpcResponse.getResponse().getSuccess()) {
                log.warn("List users failed: {}", grpcResponse.getResponse().getError().getMessage());
                return ApiResponse.error(
                        grpcResponse.getResponse().getError().getCode(),
                        grpcResponse.getResponse().getError().getMessage()
                );
            }

            List<UserResponse> users = grpcResponse.getUsersList().stream()
                    .map(u -> UserResponse.builder()
                            .userId(u.getUserId())
                            .email(u.getEmail())
                            .firstName(u.getFirstName())
                            .lastName(u.getLastName())
                            .phone(u.getPhone())
                            .role(u.getRole().name())
                            .isEmailVerified(u.getIsEmailVerified())
                            .createdAt(u.getCreatedAt().getSeconds())
                            .build())
                    .collect(Collectors.toList());

            log.info("Retrieved {} users", users.size());
            return ApiResponse.success(users);

        } catch (StatusRuntimeException e) {
            log.error("gRPC error during list users: {}", e.getStatus());
            return ApiResponse.error("GRPC_ERROR", "Service temporarily unavailable: " + e.getStatus().getDescription());
        } catch (Exception e) {
            log.error("Unexpected error during list users", e);
            return ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred");
        }
    }

    /**
     * Delete a user (admin only)
     */
    public ApiResponse<String> deleteUser(String userId) {
        try {
            log.info("gRPC Delete user request for userId: {}", userId);

            DeleteUserRequest grpcRequest = DeleteUserRequest.newBuilder()
                    .setUserId(userId)
                    .build();

            kz.courier.common.v1.Response grpcResponse = authStub.deleteUser(grpcRequest);

            if (!grpcResponse.getSuccess()) {
                log.warn("Delete user failed: {}", grpcResponse.getError().getMessage());
                return ApiResponse.error(
                        grpcResponse.getError().getCode(),
                        grpcResponse.getError().getMessage()
                );
            }

            log.info("User deleted successfully: {}", userId);
            return ApiResponse.success("User deleted successfully");

        } catch (StatusRuntimeException e) {
            log.error("gRPC error during delete user: {}", e.getStatus());
            return ApiResponse.error("GRPC_ERROR", "Service temporarily unavailable: " + e.getStatus().getDescription());
        } catch (Exception e) {
            log.error("Unexpected error during delete user", e);
            return ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred");
        }
    }

    /**
     * Get user profile by userId
     */
    public ApiResponse<UserResponse> getProfile(String userId) {
        try {
            log.info("gRPC GetUser request for userId: {}", userId);

            GetUserRequest grpcRequest = GetUserRequest.newBuilder()
                    .setUserId(userId)
                    .build();

            GetUserResponse grpcResponse = authStub.getUser(grpcRequest);

            UserResponse profile = UserResponse.builder()
                    .userId(grpcResponse.getUserId())
                    .email(grpcResponse.getEmail())
                    .firstName(grpcResponse.getFirstName())
                    .lastName(grpcResponse.getLastName())
                    .phone(grpcResponse.getPhone())
                    .role(grpcResponse.getRole().name())
                    .isEmailVerified(grpcResponse.getIsEmailVerified())
                    .createdAt(grpcResponse.getCreatedAt().getSeconds())
                    .build();

            return ApiResponse.success(profile);

        } catch (StatusRuntimeException e) {
            log.error("gRPC error during getProfile: {}", e.getStatus());
            return ApiResponse.error("GRPC_ERROR", "Service temporarily unavailable: " + e.getStatus().getDescription());
        } catch (Exception e) {
            log.error("Unexpected error during getProfile", e);
            return ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred");
        }
    }

    /**
     * Update user profile (firstName, lastName, email, phone)
     */
    public ApiResponse<String> updateProfile(String userId, kz.courier.apigateway.dto.request.UpdateProfileRequest request) {
        try {
            log.info("gRPC UpdateUser request for userId: {}", userId);

            UpdateUserRequest.Builder grpcRequest = UpdateUserRequest.newBuilder()
                    .setUserId(userId);

            if (request.getFirstName() != null) grpcRequest.setFirstName(request.getFirstName());
            if (request.getLastName() != null)  grpcRequest.setLastName(request.getLastName());
            if (request.getEmail() != null)     grpcRequest.setEmail(request.getEmail());
            if (request.getPhone() != null)     grpcRequest.setPhone(request.getPhone());

            kz.courier.common.v1.Response grpcResponse = authStub.updateUser(grpcRequest.build());

            if (!grpcResponse.getSuccess()) {
                log.warn("Update profile failed: {}", grpcResponse.getError().getMessage());
                return ApiResponse.error(
                        grpcResponse.getError().getCode(),
                        grpcResponse.getError().getMessage()
                );
            }

            log.info("Profile updated successfully for userId: {}", userId);
            return ApiResponse.success("Profile updated successfully");

        } catch (StatusRuntimeException e) {
            log.error("gRPC error during updateProfile: {}", e.getStatus());
            return ApiResponse.error("GRPC_ERROR", "Service temporarily unavailable: " + e.getStatus().getDescription());
        } catch (Exception e) {
            log.error("Unexpected error during updateProfile", e);
            return ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred");
        }
    }
}
