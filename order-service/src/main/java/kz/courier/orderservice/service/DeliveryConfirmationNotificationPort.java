package kz.courier.orderservice.service;

import kz.courier.orderservice.model.Order;

import java.time.OffsetDateTime;

/**
 * Boundary for sending delivery OTP to the customer.
 *
 * <p>Today this implementation only logs the event. Later it can publish Kafka
 * to notification-service or call notification-service synchronously without
 * touching order status or OTP verification logic.
 */
public interface DeliveryConfirmationNotificationPort {

    void sendDeliveryConfirmationCode(Order order, String code, OffsetDateTime expiresAt);
}
