package kz.courier.apigateway.dto.request.order;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemDto {
    private String itemId;
    private String name;
    private Integer quantity;
    private Double price;
}
