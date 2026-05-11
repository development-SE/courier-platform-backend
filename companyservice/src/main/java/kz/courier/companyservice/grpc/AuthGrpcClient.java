package kz.courier.companyservice.grpc;

import io.grpc.StatusRuntimeException;
import kz.courier.auth.v1.*;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Slf4j
@Service
public class AuthGrpcClient {

    @GrpcClient("auth-service")
    private AuthServiceGrpc.AuthServiceBlockingStub authStub;

    /**
     * Register a user in auth-service with the specified role.
     * Supported roles: DIRECTOR, MANAGER.
     * Returns the userId (UUID string) on success, throws on failure.
     */
    public String registerUser(String email, String password,
                               String firstName, String lastName,
                               String phone, String roleName,
                               UUID companyId) {
        Role grpcRole = toGrpcRole(roleName);
        try {
            RegisterRequest.Builder request = RegisterRequest.newBuilder()
                    .setEmail(email)
                    .setPassword(password)
                    .setFirstName(firstName)
                    .setLastName(lastName)
                    .setPhone(phone != null ? phone : "")
                    .setPushConsent(false)
                    .setRole(grpcRole);

            if (companyId != null) {
                request.setCompanyId(companyId.toString());
            }

            RegisterResponse response = authStub.register(request.build());

            if (!response.getResponse().getSuccess()) {
                String errorMsg = response.getResponse().getError().getMessage();
                throw new RuntimeException("Auth registration failed: " + errorMsg);
            }

            log.info("Registered {} in auth-service with userId: {}", grpcRole, response.getUserId());
            return response.getUserId();

        } catch (StatusRuntimeException e) {
            log.error("gRPC error registering user: {}", e.getStatus());
            throw new RuntimeException("Auth service unavailable: " + e.getStatus().getDescription());
        }
    }

    /**
     * Delete the paired auth-service user for an employee account.
     */
    public void deleteUser(UUID authUserId) {
        if (authUserId == null) {
            return;
        }

        try {
            var response = authStub.deleteUser(DeleteUserRequest.newBuilder()
                    .setUserId(authUserId.toString())
                    .build());

            if (!response.getSuccess()) {
                String errorMsg = response.hasError()
                        ? response.getError().getMessage()
                        : "Unknown auth delete error";
                throw new RuntimeException("Auth deletion failed: " + errorMsg);
            }

            log.info("Deleted auth-service user: {}", authUserId);
        } catch (StatusRuntimeException e) {
            log.error("gRPC error deleting auth user {}: {}", authUserId, e.getStatus());
            throw new RuntimeException("Auth service unavailable: " + e.getStatus().getDescription());
        }
    }

    private Role toGrpcRole(String roleName) {
        if (roleName == null || roleName.isBlank()) return Role.MANAGER;
        return switch (roleName.toUpperCase()) {
            case "DIRECTOR" -> Role.DIRECTOR;
            case "MANAGER"  -> Role.MANAGER;
            default -> throw new IllegalArgumentException(
                    "Unsupported employee role: " + roleName + ". Allowed: DIRECTOR, MANAGER");
        };
    }
}
