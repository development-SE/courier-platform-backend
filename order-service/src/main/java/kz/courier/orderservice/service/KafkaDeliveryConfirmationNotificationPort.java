package kz.courier.orderservice.service;

import kz.courier.orderservice.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaDeliveryConfirmationNotificationPort implements DeliveryConfirmationNotificationPort {

    private static final String EVENT_TYPE = "delivery_confirmation_code_created";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${notification.kafka.topic:notification-kafka-topic}")
    private String notificationTopic;

    @Override
    public void sendDeliveryConfirmationCode(Order order, String code, OffsetDateTime expiresAt) {
        NotificationEvent event = new NotificationEvent(
                UUID.randomUUID().toString(),
                order.getAuthorId().toString(),
                EVENT_TYPE,
                Map.of(
                        "order_id", order.getId().toString(),
                        "code", code,
                        "expires_at", expiresAt.toString()
                ),
                Instant.now().toString());

        kafkaTemplate.send(notificationTopic, order.getAuthorId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[DeliveryConfirmation] Failed to publish push event orderId={}: {}",
                                order.getId(), ex.getMessage());
                    } else {
                        log.info("[DeliveryConfirmation] Push event published orderId={} userId={}",
                                order.getId(), order.getAuthorId());
                    }
                });
    }

    private record NotificationEvent(
            String event_id,
            String user_id,
            String type,
            Map<String, Object> payload,
            String created_at
    ) {
    }
}
