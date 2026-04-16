package kz.courier.orderservice.security;

import io.grpc.*;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

/**
 * gRPC server-side interceptor that enforces authentication on every inbound call.
 *
 * <h3>Current mode — Gateway-Trust</h3>
 * The API Gateway is the single authentication point. It validates the JWT and
 * propagates the caller's identity via three gRPC metadata keys:
 * <ul>
 *   <li>{@code x-user-id}    – UUID of the authenticated user (required)</li>
 *   <li>{@code x-user-roles} – comma-separated role list (required)</li>
 *   <li>{@code x-user-email} – email address (optional)</li>
 * </ul>
 *
 * <h3>Future mode — Zero-Trust (JWT validation per service)</h3>
 * When the system graduates to zero-trust, simply:
 * <ol>
 *   <li>Add {@code io.jsonwebtoken:jjwt-api/impl/jackson} to {@code pom.xml}.</li>
 *   <li>Add a {@code JwtUtil} bean (copy from {@code api-gateway}).</li>
 *   <li>Inject {@code JwtUtil} into this interceptor via constructor.</li>
 *   <li>Uncomment the JWT block below — no service/business logic changes needed.</li>
 * </ol>
 * The raw JWT is already forwarded in the {@code Authorization} gRPC metadata key
 * by the gateway's {@code AuthForwardingInterceptor}, so zero gateway changes are
 * required when you flip the switch.
 */
@Slf4j
public class AuthInterceptor implements ServerInterceptor {

    // ── Metadata keys ─────────────────────────────────────────────────────────

    /** JWT bearer token — forwarded by the gateway; used by the future zero-trust path. */
    static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    static final Metadata.Key<String> USER_ID_KEY =
            Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER);

    static final Metadata.Key<String> USER_ROLES_KEY =
            Metadata.Key.of("x-user-roles", Metadata.ASCII_STRING_MARSHALLER);

    static final Metadata.Key<String> USER_ROLE_KEY =
            Metadata.Key.of("x-user-role", Metadata.ASCII_STRING_MARSHALLER);

    static final Metadata.Key<String> USER_EMAIL_KEY =
            Metadata.Key.of("x-user-email", Metadata.ASCII_STRING_MARSHALLER);

    static final Metadata.Key<String> COMPANY_ID_KEY =
            Metadata.Key.of("x-company-id", Metadata.ASCII_STRING_MARSHALLER);

    // ── Interceptor ───────────────────────────────────────────────────────────

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String method = call.getMethodDescriptor().getFullMethodName();
        log.info("[AuthInterceptor] Intercepting call to {} with metadata keys: {}", method, headers.keys());

        // ── Stage 1: JWT validation (FUTURE — zero-trust mode) ────────────────
        // Uncomment when ready to validate tokens independently.
        // Requires: jjwt dependency + JwtUtil bean injected via constructor.
        //
        // String rawAuth = headers.get(AUTHORIZATION_KEY);
        // if (rawAuth != null && rawAuth.startsWith("Bearer ")) {
        //     String token = rawAuth.substring(7);
        //     try {
        //         Claims claims = jwtUtil.extractAllClaims(token);  // throws if invalid/expired
        //         String userId = claims.getSubject();
        //         List<String> roles = parseRoles(claims.get("roles", String.class));
        //         AuthenticatedUser authUser = new AuthenticatedUser(userId, roles, null);
        //         log.debug("[AuthInterceptor] JWT validated userId={} roles={} method={}", userId, roles, method);
        //         Context ctx = Context.current().withValue(GrpcAuthContext.AUTHENTICATED_USER_KEY, authUser);
        //         return Contexts.interceptCall(ctx, call, headers, next);
        //     } catch (Exception e) {
        //         log.warn("[AuthInterceptor] JWT validation failed: {} method={}", e.getMessage(), method);
        //         // Fall through to metadata fallback or reject below
        //     }
        // }

        // ── Stage 2: Gateway-forwarded metadata (current mode) ────────────────
        // Trusts identity headers set exclusively by the API-Gateway's
        // JwtAuthenticationFilter. Spoofed client headers are stripped by the
        // gateway before the gRPC call is made.
        String userId   = headers.get(USER_ID_KEY);
        String rolesRaw = headers.get(USER_ROLES_KEY);
        if (isBlank(rolesRaw)) {
            rolesRaw = headers.get(USER_ROLE_KEY);
        }

        if (isBlank(userId) || isBlank(rolesRaw)) {
            log.warn("[AuthInterceptor] Missing auth metadata — userId='{}' roles='{}' method={}",
                    userId, rolesRaw, method);
            call.close(
                    Status.UNAUTHENTICATED.withDescription(
                            "Missing authentication metadata (x-user-id, x-user-roles or x-user-role)"),
                    new Metadata());
            return new ServerCall.Listener<>() {};
        }

        List<String> roles = parseRoles(rolesRaw);
        String email = headers.get(USER_EMAIL_KEY); // optional
        String companyId = headers.get(COMPANY_ID_KEY); // optional

        AuthenticatedUser authUser = new AuthenticatedUser(userId, roles, email, companyId);
        log.debug("[AuthInterceptor] Authorized userId={} roles={} method={}", userId, roles, method);

        Context ctx = Context.current()
                .withValue(GrpcAuthContext.AUTHENTICATED_USER_KEY, authUser);
        return Contexts.interceptCall(ctx, call, headers, next);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static List<String> parseRoles(String rolesRaw) {
        if (rolesRaw == null || rolesRaw.isBlank()) return List.of();
        return Arrays.stream(rolesRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
