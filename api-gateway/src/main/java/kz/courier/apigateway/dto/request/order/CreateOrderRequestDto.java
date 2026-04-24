package kz.courier.apigateway.dto.request.order;

import kz.courier.order.v1.ServiceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderRequestDto {
    private List<OrderItemDto> items;
    private String companyId;
    private String serviceType; // STANDARD, SCHEDULED, EXPRESS
    private String comment;
    private AddressDto deliveryAddress;
    private ContactInfoDto recipientInfo;
    private AddressDto pickupAddress;
    private ContactInfoDto pickupInfo;
}

