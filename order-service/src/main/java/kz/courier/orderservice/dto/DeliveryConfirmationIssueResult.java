package kz.courier.orderservice.dto;

import kz.courier.orderservice.model.OrderStatus;

import java.time.OffsetDateTime;

public record DeliveryConfirmationIssueResult(
        OrderStatus status,
        OffsetDateTime expiresAt,
        boolean regenerated
) {
}
