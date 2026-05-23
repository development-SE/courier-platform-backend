package kz.courier.orderservice.service;

import kz.courier.orderservice.model.Order;
import lombok.extern.slf4j.Slf4j;

import java.time.OffsetDateTime;

@Slf4j
public class LoggingDeliveryConfirmationNotificationPort implements DeliveryConfirmationNotificationPort {

    @Override
    public void sendDeliveryConfirmationCode(Order order, String code, OffsetDateTime expiresAt) {
        log.info("[DeliveryConfirmation] notification placeholder orderId={} authorId={} expiresAt={}",
                order.getId(), order.getAuthorId(), expiresAt);
    }
}
