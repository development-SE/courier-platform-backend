package kz.courier.apigateway.grpc;

/**
 * Immutable carrier of per-request authentication data extracted by
 * {@link kz.courier.apigateway.filter.JwtAuthenticationFilter} and passed
 * explicitly to {@link OrderClient}.
 *
 * <p>Using a dedicated record (rather than a {@code ThreadLocal}) makes the auth
 * flow fully explicit and safe under any concurrency model (blocking, reactive,
 * virtual-thread-based, etc.).
 *
 * @param userId  UUID of the authenticated user, never {@code null} on success
 * @param roles   comma-separated role string (e.g. {@code "CLIENT,ADMIN"}), never {@code null}
 * @param token   raw JWT bearer token forwarded verbatim; may be {@code null} if
 *                the caller only has pre-extracted metadata (unusual in practice)
 */
public record AuthContext(String userId, String roles, String token) {

    /**
     * Convenience factory — the typical case after JWT parsing.
     */
    public static AuthContext of(String userId, String roles, String token) {
        return new AuthContext(userId, roles, token);
    }
}
