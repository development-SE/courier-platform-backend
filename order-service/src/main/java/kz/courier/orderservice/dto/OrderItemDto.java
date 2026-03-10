package kz.courier.orderservice.dto;

import lombok.*;

/**
 * DTO mirroring the gRPC {@code OrderItem} message.
 *
 * <p>Stored as JSONB inside the {@code orders.items_json} column,
 * allowing flexible schema evolution without separate DB rows.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItemDto {

    /** Reference ID to partner menu item (or free-text identifier). */
    private String itemId;

    /** Human-readable item name. */
    private String name;

    /** Quantity ordered. */
    private int quantity;

    /** Unit price; may be null for items without a price (documents, services). */
    private Double price;
}
