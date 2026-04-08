package kz.courier.logisticsservice.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Forwards the same caller identity that logistics-service received from the gateway.
 *
 * <p>This preserves the current gateway-trust model even for synchronous
 * service-to-service gRPC calls.
 */
@Slf4j
@RequiredArgsConstructor
public class AuthForwardingInterceptor implements ClientInterceptor {

    private static final Metadata.Key<String> USER_ID_KEY =
            Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> USER_ROLES_KEY =
            Metadata.Key.of("x-user-roles", Metadata.ASCII_STRING_MARSHALLER);

    private final String userId;
    private final String userRoles;

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {

        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                headers.put(USER_ID_KEY, userId);
                headers.put(USER_ROLES_KEY, userRoles);

                log.debug("Forwarding trusted auth to gRPC method={} userId={} roles={}",
                        method.getFullMethodName(), userId, userRoles);

                super.start(responseListener, headers);
            }
        };
    }
}
