package kz.courier.orderservice.security;

import java.util.List;

/**
 * Immutable value object that holds the authenticated caller's identity,
 * extracted from gRPC metadata by {@link AuthInterceptor}.
 */
public record AuthenticatedUser(
        String userId,
        List<String> roles,
        String email,
        String companyId
) {
    public AuthenticatedUser(String userId, List<String> roles, String email) {
        this(userId, roles, email, null);
    }

    /** Convenience: check if the caller has at least one of the given roles. */
    public boolean hasRole(String... required) {
        for (String role : required) {
            if (roles.contains(role)) return true;
        }
        return false;
    }
}
