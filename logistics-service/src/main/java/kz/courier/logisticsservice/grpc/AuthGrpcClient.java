package kz.courier.logisticsservice.grpc;

import io.grpc.StatusRuntimeException;
import kz.courier.auth.v1.AuthServiceGrpc;
import kz.courier.auth.v1.GetUserRequest;
import kz.courier.auth.v1.GetUserResponse;
import kz.courier.logisticsservice.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGrpcClient {

    @GrpcClient("auth-service")
    private AuthServiceGrpc.AuthServiceBlockingStub authStub;

    public AuthUser getUser(UUID userId) {
        try {
            log.info("Requesting user details from auth-service userId={}", userId);
            GetUserResponse response = authStub.getUser(GetUserRequest.newBuilder()
                    .setUserId(userId.toString())
                    .build());

            if (response.hasResponse() && !response.getResponse().getSuccess()) {
                String code = response.getResponse().getError().getCode();
                String message = response.getResponse().getError().getMessage();
                throw new BusinessException(code != null ? code : "AUTH_SERVICE_ERROR",
                        message != null ? message : "auth-service rejected the request");
            }

            return new AuthUser(
                    UUID.fromString(response.getUserId()),
                    response.getFirstName(),
                    response.getLastName(),
                    response.getPhone()
            );
        } catch (StatusRuntimeException ex) {
            log.error("gRPC call to auth-service failed userId={} error={}", userId, ex.getMessage());
            throw new BusinessException("AUTH_SERVICE_ERROR", "Failed to load user from auth-service");
        }
    }

    public record AuthUser(
            UUID userId,
            String firstName,
            String lastName,
            String phone
    ) {}
}
