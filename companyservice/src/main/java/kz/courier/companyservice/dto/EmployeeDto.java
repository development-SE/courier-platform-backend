package kz.courier.companyservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class EmployeeDto {

    /** Roles allowed when creating an employee via /employees. */
    public enum EmployeeRole {
        DIRECTOR, MANAGER
    }

    @Data
    public static class CreateRequest {
        @NotBlank(message = "First name is required")
        @Pattern(regexp = "^[a-zA-Zа-яА-Я\\s-]{2,100}$")
        private String firstName;

        @NotBlank(message = "Last name is required")
        @Pattern(regexp = "^[a-zA-Zа-яА-Я\\s-]{2,100}$")
        private String lastName;

        @NotBlank(message = "Email is required")
        @Email
        private String email;

        @Pattern(regexp = "^\\+?[0-9]{10,15}$")
        private String phone;

        @NotBlank(message = "Password is required")
        @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$",
            message = "Password must be 8+ chars with upper, lower, digit, special char"
        )
        private String password;  // sent to auth-service for registration

        // Allowed values: DIRECTOR, MANAGER. Defaults to MANAGER when absent.
        private String role;
    }

    @Data
    public static class UpdateRequest {
        @Pattern(regexp = "^[a-zA-Zа-яА-Я\\s-]{2,100}$")
        private String firstName;

        @Pattern(regexp = "^[a-zA-Zа-яА-Я\\s-]{2,100}$")
        private String lastName;

        @Email
        private String email;

        @Pattern(regexp = "^\\+?[0-9]{10,15}$")
        private String phone;
    }

    @Data @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Response {
        private UUID id;
        private String firstName;
        private String lastName;
        private String email;
        private String phone;
        private UUID companyId;
        private UUID authUserId;
        private String role;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data @Builder
    public static class PageResponse {
        private List<Response> content;
        private int page;
        private int pageSize;
        private long totalItems;
        private int totalPages;
    }
}