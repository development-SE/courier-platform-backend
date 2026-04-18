package kz.courier.orderservice.dto;

import java.time.OffsetDateTime;

public record DeliveryConfirmationCodeView(
        String code,
        OffsetDateTime expiresAt,
        int attemptsRemaining
) {
}
