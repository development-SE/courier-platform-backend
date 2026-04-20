package kz.courier.notification.service;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class GatewayPrincipalProvider {

    public UUID requireUserId(String userIdHeader) {
        try {
            return UUID.fromString(userIdHeader);
        } catch (Exception e) {
            throw new IllegalArgumentException("Missing or invalid X-User-Id header");
        }
    }

    public boolean hasAnyRole(String rolesHeader, String... roles) {
        if (rolesHeader == null || rolesHeader.isBlank()) {
            return false;
        }
        Set<String> currentRoles = Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .collect(Collectors.toSet());
        return Arrays.stream(roles)
                .filter(role -> role != null && !role.isBlank())
                .anyMatch(currentRoles::contains);
    }
}
