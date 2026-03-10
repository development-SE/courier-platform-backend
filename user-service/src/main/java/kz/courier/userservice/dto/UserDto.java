package kz.courier.userservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import kz.courier.userservice.model.Role;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

// ── Request DTOs ─────────────────────────────────────────────────────────────

public class UserDto {

    @Data
    public static class CreateRequest {
        @NotBlank(message = "First name is required")
        @Pattern(regexp = "^[a-zA-Zа-яА-Я\\s-]{2,100}$", message = "Invalid first name")
        private String firstName;

        @NotBlank(message = "Last name is required")
        @Pattern(regexp = "^[a-zA-Zа-яА-Я\\s-]{2,100}$", message = "Invalid last name")
        private String lastName;

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        private String email;

        @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "Invalid phone number")
        private String phone;

        private UUID companyId;

        @NotNull(message = "Role is required")
        private Role role;
    }

    @Data
    public static class UpdateRequest {
        @Pattern(regexp = "^[a-zA-Zа-яА-Я\\s-]{2,100}$", message = "Invalid first name")
        private String firstName;

        @Pattern(regexp = "^[a-zA-Zа-яА-Я\\s-]{2,100}$", message = "Invalid last name")
        private String lastName;

        @Email(message = "Invalid email format")
        private String email;

        @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "Invalid phone number")
        private String phone;

        private UUID companyId;

        private Role role;

        private Boolean active;
    }

    // ── Response DTO ─────────────────────────────────────────────────────────

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Response {
        private UUID id;
        private String firstName;
        private String lastName;
        private String email;
        private String phone;
        private UUID companyId;
        private Role role;
        private boolean active;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    // ── Paginated list response ───────────────────────────────────────────────

    @Data
    @Builder
    public static class PageResponse {
        private java.util.List<Response> content;
        private int page;
        private int pageSize;
        private long totalItems;
        private int totalPages;
    }
}
