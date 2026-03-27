package kz.courier.apigateway.dto.request.order;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddressDto {
    private String type; // USER, COMPANY
    private String city;
    private String street;
    private String house;
    private String apartment;
    private String entrance;
    private String floor;
    private Double latitude;
    private Double longitude;
}
