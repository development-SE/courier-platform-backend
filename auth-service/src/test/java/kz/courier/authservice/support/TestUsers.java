package kz.courier.authservice.support;

import kz.courier.authservice.model.Role;
import kz.courier.authservice.model.User;

import java.time.LocalDateTime;
import java.util.UUID;

public final class TestUsers {

    private TestUsers() {
    }

    public static User activeVerifiedUser() {
        return user(Role.CLIENT, null, true, true);
    }

    public static User companyManager(UUID companyId) {
        return user(Role.MANAGER, companyId, true, true);
    }

    public static User inactiveVerifiedUser() {
        return user(Role.CLIENT, null, false, true);
    }

    private static User user(Role role, UUID companyId, boolean active, boolean emailVerified) {
        LocalDateTime now = LocalDateTime.now();
        return User.builder()
                .id(UUID.randomUUID())
                .email("user-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$encoded")
                .firstName("Test")
                .lastName("User")
                .role(role)
                .companyId(companyId)
                .active(active)
                .emailVerified(emailVerified)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
