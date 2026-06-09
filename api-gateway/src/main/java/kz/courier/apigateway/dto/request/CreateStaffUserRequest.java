package kz.courier.apigateway.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateStaffUserRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Password is required")
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$",
            message = "Password must be 8+ chars with upper, lower, digit, and special character"
    )
    private String password;

    private String phone;

    @NotBlank(message = "First name is required")
    @Size(min = 2, max = 100, message = "First name must be 2-100 characters")
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(min = 2, max = 100, message = "Last name must be 2-100 characters")
    private String lastName;

    @Builder.Default
    private Boolean pushConsent = false;

    @NotBlank(message = "Role is required")
    private String role;

    private UUID companyId;
}
