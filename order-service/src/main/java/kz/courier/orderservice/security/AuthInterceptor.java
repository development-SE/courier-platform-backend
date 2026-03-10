package kz.courier.orderservice.security;

import io.grpc.*;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

/**
 * gRPC server-side interceptor that enforces authentication for the order-service.
 *
 * <p>The API Gateway is responsible for authenticating clients and then forwarding
 * requests to this service with authentication metadata injected in the gRPC headers:
 * <ul>
 *   <li>{@code x-user-id}    – UUID of the authenticated user (required)</li>
 *   <li>{@code x-user-roles} – comma-separated role list, e.g. {@code CLIENT,ADMIN} (required)</li>
 *   <li>{@code x-user-email} – email address (optional)</li>
 * </ul>
 *
 * <p>If the mandatory metadata is absent the interceptor terminates the call immediately
 * with {@link Status#UNAUTHENTICATED}.
 */
@Slf4j
public class AuthInterceptor implements ServerInterceptor {

    // ── Metadata keys ────────────────────────────────────────────────────────

    static final Metadata.Key<String> USER_ID_KEY =
            Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER);

    static final Metadata.Key<String> USER_ROLES_KEY =
            Metadata.Key.of("x-user-roles", Metadata.ASCII_STRING_MARSHALLER);

    static final Metadata.Key<String> USER_EMAIL_KEY =
            Metadata.Key.of("x-user-email", Metadata.ASCII_STRING_MARSHALLER);

    // ── Interceptor ──────────────────────────────────────────────────────────

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String userId = headers.get(USER_ID_KEY);
        String rolesRaw = headers.get(USER_ROLES_KEY);

        // Both userId and roles are mandatory; reject the call if missing
        if (isBlank(userId) || isBlank(rolesRaw)) {
            log.warn("[AuthInterceptor] Missing auth metadata on method={} userId='{}' roles='{}'",
                    call.getMethodDescriptor().getFullMethodName(), userId, rolesRaw);
            call.close(
                    Status.UNAUTHENTICATED.withDescription(
                            "Missing authentication metadata (x-user-id, x-user-roles)"),
                    new Metadata());
            return new ServerCall.Listener<>() {};
        }

        List<String> roles = Arrays.stream(rolesRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        String email = headers.get(USER_EMAIL_KEY); // may be null — optional

        AuthenticatedUser authUser = new AuthenticatedUser(userId, roles, email);
        log.debug("[AuthInterceptor] Authenticated userId={} roles={}", userId, roles);

        // Store the user in gRPC Context and delegate to the actual handler
        Context ctx = Context.current()
                .withValue(GrpcAuthContext.AUTHENTICATED_USER_KEY, authUser);
        return Contexts.interceptCall(ctx, call, headers, next);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
