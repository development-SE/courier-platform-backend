package kz.courier.orderservice.security;

import java.util.List;

/**
 * Immutable value object that holds the authenticated caller's identity,
 * extracted from gRPC metadata by {@link AuthInterceptor}.
 */
public record AuthenticatedUser(
        String userId,
        List<String> roles,
        String email        // may be null — optional field from gateway
) {
    /** Convenience: check if the caller has at least one of the given roles. */
    public boolean hasRole(String... required) {
        for (String r : required) {
            if (roles.contains(r)) return true;
        }
        return false;
    }
}
