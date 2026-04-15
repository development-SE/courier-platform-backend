package kz.courier.apigateway.grpc;

import io.grpc.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * gRPC {@link ClientInterceptor} that attaches authentication metadata to specific
 * outbound gRPC calls made by the API Gateway.
 *
 * <p>Three metadata keys are forwarded:
 * <ul>
 *   <li>{@code Authorization} – raw JWT as {@code Bearer <token>} (for future server-side
 *       JWT validation without any gateway changes)</li>
 *   <li>{@code x-user-id}    – UUID of the authenticated user</li>
 *   <li>{@code x-user-roles} – comma-separated role list</li>
 * </ul>
 *
 * <p>This interceptor is entirely thread-safe and reactive-friendly, as it relies on
 * explicit constructor parameters passed on a per-request stub basis rather than
 * {@code ThreadLocal} variables.
 */
@Slf4j
@RequiredArgsConstructor
public class AuthForwardingInterceptor implements ClientInterceptor {

    private final String userId;
    private final String userRoles;
    private final String token;
    private final String companyId;

    // Standard HTTP Authorization header — used so downstream services can
    // perform independent JWT validation in the future with zero changes here.
    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private static final Metadata.Key<String> USER_ID_KEY =
            Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER);

    private static final Metadata.Key<String> USER_ROLES_KEY =
            Metadata.Key.of("x-user-roles", Metadata.ASCII_STRING_MARSHALLER);

    private static final Metadata.Key<String> COMPANY_ID_KEY =
            Metadata.Key.of("x-company-id", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {

        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {

                if (userId != null && userRoles != null) {
                    headers.put(USER_ID_KEY,    userId);
                    headers.put(USER_ROLES_KEY, userRoles);

                    // Also forward the raw JWT so downstream services can validate
                    // independently when zero-trust mode is enabled.
                    if (token != null) {
                        headers.put(AUTHORIZATION_KEY, "Bearer " + token);
                    }
                    if (companyId != null && !companyId.isBlank()) {
                        headers.put(COMPANY_ID_KEY, companyId);
                    }

                    log.info("[AuthForwarding] x-user-id={} x-user-roles={} x-company-id={} jwt={}",
                            userId, userRoles, companyId, token != null ? "present" : "absent");
                } else {
                    log.warn("[AuthForwarding] Missing auth details — proceeding unauthenticated.");
                }

                super.start(responseListener, headers);
            }
        };
    }
}
