package kz.courier.orderservice.config;

import kz.courier.orderservice.security.AuthInterceptor;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.context.annotation.Configuration;

/**
 * Registers global gRPC server interceptors for the order-service.
 *
 * <p>The {@link AuthInterceptor} is applied to every inbound gRPC call,
 * ensuring that only requests forwarded by the API Gateway (with proper
 * authentication metadata) are processed.
 */
@Configuration(proxyBeanMethods = false)
public class GrpcServerConfig {

    /**
     * Registers {@link AuthInterceptor} as a global gRPC server interceptor.
     * The annotation {@code @GrpcGlobalServerInterceptor} from grpc-spring-boot-starter
     * ensures it is discovered and applied automatically.
     */
    @GrpcGlobalServerInterceptor
    public AuthInterceptor authInterceptor() {
        return new AuthInterceptor();
    }
}
