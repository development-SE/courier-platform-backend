package kz.courier.apigateway.dto.request.order;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContactInfoDto {
    private String name;
    private String surname;
    private String phone;
}
