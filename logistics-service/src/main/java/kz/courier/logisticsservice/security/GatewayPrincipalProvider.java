package kz.courier.logisticsservice.security;

import kz.courier.logisticsservice.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves the authenticated principal reconstructed from trusted gateway headers.
 *
 * <p>The logistics-service intentionally does not parse JWTs again. It trusts
 * the {@code X-User-Id} and {@code X-User-Roles} values already validated by
 * the API Gateway and converted into Spring Security authentication.
 */
@Component
public class GatewayPrincipalProvider {

    /**
     * Returns the current trusted principal or fails fast if the request lacks
     * gateway-provided identity metadata.
     */
    public GatewayPrincipal requireCurrentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new BusinessException("UNAUTHENTICATED",
                    "Missing trusted gateway principal in security context");
        }

        String rolesCsv = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(this::stripRolePrefix)
                .collect(Collectors.joining(","));

        return new GatewayPrincipal(authentication.getName(), rolesCsv);
    }

    /**
     * Returns the authenticated user id as UUID for audit fields such as
     * {@code assignedBy}.
     */
    public UUID requireCurrentUserId() {
        try {
            return UUID.fromString(requireCurrentPrincipal().userId());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("INVALID_GATEWAY_PRINCIPAL",
                    "Authenticated user id from gateway is not a valid UUID");
        }
    }

    /**
     * Returns {@code true} when the current principal has the requested role.
     */
    public boolean hasRole(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        Set<String> roles = requireCurrentPrincipal().roles();
        return roles.contains("SUPER_ADMIN") || roles.contains(role);
    }

    /**
     * Returns {@code true} when the current principal has at least one of the
     * requested roles.
     */
    public boolean hasAnyRole(String... roles) {
        if (roles == null || roles.length == 0) {
            return false;
        }

        Set<String> currentRoles = requireCurrentPrincipal().roles();
        if (currentRoles.contains("SUPER_ADMIN")) {
            return true;
        }
        for (String role : roles) {
            if (role != null && currentRoles.contains(role)) {
                return true;
            }
        }
        return false;
    }

    private String stripRolePrefix(String authority) {
        if (authority == null || authority.isBlank()) {
            return "";
        }
        return authority.startsWith("ROLE_") ? authority.substring(5) : authority;
    }

    /**
     * Immutable identity snapshot propagated from the gateway.
     */
    public record GatewayPrincipal(String userId, String rolesCsv) {

        public Set<String> roles() {
            if (rolesCsv == null || rolesCsv.isBlank()) {
                return Set.of();
            }
            return java.util.Arrays.stream(rolesCsv.split(","))
                    .map(String::trim)
                    .filter(role -> !role.isBlank())
                    .collect(Collectors.toUnmodifiableSet());
        }
    }
}
