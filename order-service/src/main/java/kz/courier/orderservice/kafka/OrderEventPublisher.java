package kz.courier.orderservice.kafka;

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

    @Value("${kafka.topic.order-events:kafka-order-events}")
    private String orderEventsTopic;

    public void publishCreatedAfterCommit(Order order) {
        OrderCreatedEvent event = OrderCreatedEvent.from("ORDER_CREATED", order);
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

    public void publishReadyAfterCommit(Order order) {
        OrderCreatedEvent event = OrderCreatedEvent.from("ORDER_READY", order);
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

    public void publishStatusChangedAfterCommit(Order order) {
        OrderCreatedEvent event = OrderCreatedEvent.from("ORDER_STATUS_CHANGED", order);
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
        kafkaTemplate.send(orderEventsTopic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[OrderEventPublisher] Failed to publish order-created orderId={}: {}",
                                key, ex.getMessage());
                    } else {
                        log.info("[OrderEventPublisher] Published order-created orderId={} topic={} offset={}",
                                key, orderEventsTopic, result.getRecordMetadata().offset());
                    }
                });
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
            String status,
            String serviceType,
            String parcelSize,
            OffsetDateTime createdAt
    ) {
        static OrderCreatedEvent from(String eventType, Order order) {
            return new OrderCreatedEvent(
                    eventType,
                    order.getId(),
                    order.getAuthorId(),
                    order.getCompanyId(),
                    order.getPickupAddress() == null ? null : order.getPickupAddress().getLatitude(),
                    order.getPickupAddress() == null ? null : order.getPickupAddress().getLongitude(),
                    order.getDeliveryAddress() == null ? null : order.getDeliveryAddress().getLatitude(),
                    order.getDeliveryAddress() == null ? null : order.getDeliveryAddress().getLongitude(),
                    order.getStatus() == null ? null : order.getStatus().name(),
                    order.getServiceType() == null ? null : order.getServiceType().name(),
                    order.getParcelSize() == null ? null : order.getParcelSize().name(),
                    order.getCreatedAt()
            );
        }
    }
}
