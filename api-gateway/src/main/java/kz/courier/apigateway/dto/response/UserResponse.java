package kz.courier.apigateway.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {
    private String userId;
    private String email;
    private String firstName;
    private String lastName;
    private String phone;
    private String role;
    private Boolean isEmailVerified;
    private Long createdAt;
}