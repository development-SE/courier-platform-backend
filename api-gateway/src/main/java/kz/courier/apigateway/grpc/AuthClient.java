package kz.courier.apigateway.grpc;

import io.grpc.StatusRuntimeException;
import kz.courier.apigateway.dto.response.ApiResponse;
import kz.courier.apigateway.dto.response.LoginResponse;
import kz.courier.apigateway.dto.response.RefreshTokenResponse;
import kz.courier.apigateway.dto.response.RegisterResponse;
import kz.courier.auth.v1.*;
import kz.courier.common.v1.PaginationRequest;
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

            kz.courier.auth.v1.RegisterRequest grpcRequest = kz.courier.auth.v1.RegisterRequest.newBuilder()
                    .setEmail(request.getEmail())
                    .setPassword(request.getPassword())
                    .setPhone(request.getPhone() != null ? request.getPhone() : "")
                    .setFirstName(request.getFirstName())
                    .setLastName(request.getLastName())
                    .setPushConsent(request.getPushConsent() != null ? request.getPushConsent() : false)
                    .setRole(Role.valueOf(request.getRole()))
                    .build();


            kz.courier.auth.v1.RegisterResponse grpcResponse = authStub.register(grpcRequest);

            log.info("gRPC Register response: {}", grpcResponse);

            if (!grpcResponse.getResponse().getSuccess()) {
                log.warn("Registration failed: {}", grpcResponse.getResponse().getError().getMessage());
                return ApiResponse.error(
                        grpcResponse.getResponse().getError().getCode(),
                        grpcResponse.getResponse().getError().getMessage()
                );
            }

            RegisterResponse response = RegisterResponse.builder()
                    .userId(grpcResponse.getUserId())
                    .confirmationToken(grpcResponse.getConfirmationToken())
                    .message("Registration successful. Please check your email to verify your account.")
                    .build();

            log.info("User registered successfully: {}", grpcResponse.getUserId());
            return ApiResponse.success(response);

        } catch (StatusRuntimeException e) {
            log.error("gRPC error during registration: {}", e.getStatus());
            return ApiResponse.error("GRPC_ERROR", "Service temporarily unavailable: " + e.getStatus().getDescription());
        } catch (Exception e) {
            log.error("Unexpected error during registration", e);
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
        try {
            log.info("gRPC List users request - page: {}, size: {}", page, pageSize);

            ListUsersRequest grpcRequest = ListUsersRequest.newBuilder()
                    .setPagination(PaginationRequest.newBuilder()
                            .setPage(page)
                            .setPageSize(pageSize)
                            .setSortBy("created_at")
                            .setAscending(false)
                            .build())
                    .build();

            ListUsersResponse grpcResponse = authStub.listUsers(grpcRequest);

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
}