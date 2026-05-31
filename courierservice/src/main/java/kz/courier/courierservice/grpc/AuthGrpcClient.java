package kz.courier.courierservice.grpc;

import io.grpc.StatusRuntimeException;
import kz.courier.auth.v1.AuthServiceGrpc;
import kz.courier.auth.v1.GetUserRequest;
import kz.courier.auth.v1.GetUserResponse;
import kz.courier.courierservice.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
public class AuthGrpcClient {

    @GrpcClient("auth-service")
    private AuthServiceGrpc.AuthServiceBlockingStub authStub;

    public AuthUser getUser(UUID userId) {
        try {
            GetUserResponse response = authStub.getUser(GetUserRequest.newBuilder()
                    .setUserId(userId.toString())
                    .build());

            if (response.hasResponse() && !response.getResponse().getSuccess()) {
                String code = response.getResponse().getError().getCode();
                String message = response.getResponse().getError().getMessage();
                if ("USER_NOT_FOUND".equals(code)) {
                    throw new BusinessException("AUTH_USER_NOT_FOUND", message);
                }
                throw new BusinessException("AUTH_USER_INVALID", message);
            }

            UUID companyId = response.hasCompanyId() && !response.getCompanyId().isBlank()
                    ? UUID.fromString(response.getCompanyId())
                    : null;

            return new AuthUser(
                    UUID.fromString(response.getUserId()),
                    response.getRole().name(),
                    response.getIsActive(),
                    response.getIsEmailVerified(),
                    companyId
            );
        } catch (BusinessException ex) {
            throw ex;
        } catch (StatusRuntimeException ex) {
            log.warn("auth-service unavailable while loading userId={}: {}", userId, ex.getStatus());
            throw new BusinessException("AUTH_SERVICE_UNAVAILABLE",
                    "Auth service is unavailable. Courier profile was not created.");
        } catch (Exception ex) {
            log.warn("auth-service response could not be used for userId={}: {}", userId, ex.getMessage());
            throw new BusinessException("AUTH_SERVICE_UNAVAILABLE",
                    "Auth service is unavailable. Courier profile was not created.");
        }
    }

    public record AuthUser(
            UUID userId,
            String role,
            boolean active,
            boolean emailVerified,
            UUID companyId
    ) {
    }
}
