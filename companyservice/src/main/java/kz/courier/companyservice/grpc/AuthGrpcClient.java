package kz.courier.companyservice.grpc;

import io.grpc.StatusRuntimeException;
import kz.courier.auth.v1.*;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AuthGrpcClient {

    @GrpcClient("auth-service")
    private AuthServiceGrpc.AuthServiceBlockingStub authStub;

    /**
     * Register a new MANAGER user in auth-service.
     * Returns the userId (UUID string) on success, throws on failure.
     */
    public String registerManager(String email, String password,
                                   String firstName, String lastName,
                                   String phone) {
        try {
            RegisterRequest request = RegisterRequest.newBuilder()
                    .setEmail(email)
                    .setPassword(password)
                    .setFirstName(firstName)
                    .setLastName(lastName)
                    .setPhone(phone != null ? phone : "")
                    .setPushConsent(false)
                    .setRole(Role.MANAGER)
                    .build();

            RegisterResponse response = authStub.register(request);

            if (!response.getResponse().getSuccess()) {
                String errorMsg = response.getResponse().getError().getMessage();
                throw new RuntimeException("Auth registration failed: " + errorMsg);
            }

            log.info("Registered MANAGER in auth-service with userId: {}", response.getUserId());
            return response.getUserId();

        } catch (StatusRuntimeException e) {
            log.error("gRPC error registering manager: {}", e.getStatus());
            throw new RuntimeException("Auth service unavailable: " + e.getStatus().getDescription());
        }
    }
}