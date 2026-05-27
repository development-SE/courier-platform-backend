package kz.courier.orderservice.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.courier.orderservice.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${kafka.topic.order-events:kafka-order-events}")
    private String orderEventsTopic;

    public void publishCreatedAfterCommit(Order order) {
        OrderCreatedEvent event = OrderCreatedEvent.from(order);
        Runnable publish = () -> publish(order.getId().toString(), event);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    private void publish(String key, OrderCreatedEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(orderEventsTopic, key, json)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("[OrderEventPublisher] Failed to publish order-created orderId={}: {}",
                                    key, ex.getMessage());
                        } else {
                            log.info("[OrderEventPublisher] Published order-created orderId={} topic={} offset={}",
                                    key, orderEventsTopic, result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception e) {
            log.error("[OrderEventPublisher] Serialization failed orderId={}", key, e);
        }
    }

    public record OrderCreatedEvent(
            String eventType,
            UUID orderId,
            UUID clientId,
            UUID companyId,
            Double pickupLatitude,
            Double pickupLongitude,
            Double deliveryLatitude,
            Double deliveryLongitude,
            String parcelSize,
            OffsetDateTime createdAt
    ) {
        static OrderCreatedEvent from(Order order) {
            return new OrderCreatedEvent(
                    "ORDER_CREATED",
                    order.getId(),
                    order.getAuthorId(),
                    order.getCompanyId(),
                    order.getPickupAddress() == null ? null : order.getPickupAddress().getLatitude(),
                    order.getPickupAddress() == null ? null : order.getPickupAddress().getLongitude(),
                    order.getDeliveryAddress() == null ? null : order.getDeliveryAddress().getLatitude(),
                    order.getDeliveryAddress() == null ? null : order.getDeliveryAddress().getLongitude(),
                    order.getParcelSize() == null ? null : order.getParcelSize().name(),
                    order.getCreatedAt()
            );
        }
    }
}
