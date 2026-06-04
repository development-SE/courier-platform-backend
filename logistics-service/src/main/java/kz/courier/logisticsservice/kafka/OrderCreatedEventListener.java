package kz.courier.logisticsservice.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.courier.logisticsservice.exception.BusinessException;
import kz.courier.logisticsservice.security.SystemPrincipalRunner;
import kz.courier.logisticsservice.service.CapacityAwareAssignmentService;
import kz.courier.logisticsservice.service.RouteCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreatedEventListener {
    private static final Set<String> ASSIGNMENT_ELIGIBLE_ORDER_STATUSES =
            Set.of("READY", "ASSIGNMENT_PENDING");

    private final ObjectMapper objectMapper;
    private final CapacityAwareAssignmentService assignmentService;
    private final SystemPrincipalRunner systemPrincipalRunner;
    private final RouteCleanupService routeCleanupService;

    @KafkaListener(
            topics = "${kafka.topic.order-events:kafka-order-events}",
            groupId = "${spring.kafka.consumer.group-id:logistics-service}")
    public void onOrderCreated(ConsumerRecord<String, String> record) {
        handleEvent(
                record.value(),
                record.topic(),
                record.key(),
                record.partition(),
                record.offset());
    }

    public void onOrderCreated(String payload) {
        handleEvent(payload, "kafka-order-events", null, -1, -1);
    }

    private void handleEvent(String payload, String topic, String key, int partition, long offset) {
        OrderCreatedEvent event = null;
        try {
            event = objectMapper.readValue(payload, OrderCreatedEvent.class);
            if (!Set.of("ORDER_CREATED", "ORDER_READY", "ORDER_STATUS_CHANGED").contains(event.eventType())) {
                log.debug("[OrderCreatedEventListener] Ignoring event topic={} partition={} offset={} eventType={}",
                        topic, partition, offset, event.eventType());
                return;
            }
            if ("CANCELLED".equals(event.status())) {
                routeCleanupService.cleanupOrderCancellation(event.orderId(), "order-cancelled-event");
                log.info("[OrderCreatedEventListener] Cleaned logistics state for cancelled orderId={} topic={} partition={} offset={}",
                        event.orderId(), topic, partition, offset);
                return;
            }
            if (event.status() == null || !ASSIGNMENT_ELIGIBLE_ORDER_STATUSES.contains(event.status())) {
                log.debug("[OrderCreatedEventListener] Ignoring event topic={} partition={} offset={} eventType={} status={} orderId={}",
                        topic, partition, offset, event.eventType(), event.status(), event.orderId());
                return;
            }
            if (assignmentService.hasActiveAssignment(event.orderId())) {
                log.info("[OrderCreatedEventListener] Order already has active assignment orderId={} topic={} partition={} offset={}",
                        event.orderId(), topic, partition, offset);
                return;
            }

            UUID orderId = event.orderId();
            systemPrincipalRunner.run(() -> assignmentService.autoAssign(orderId));
            log.info("[OrderCreatedEventListener] Auto-assignment triggered orderId={} topic={} partition={} offset={}",
                    event.orderId(), topic, partition, offset);
        } catch (BusinessException ex) {
            if ("DUPLICATE_ASSIGNMENT".equals(ex.getCode())) {
                log.info("[OrderCreatedEventListener] Duplicate assignment ignored orderId={} topic={} partition={} offset={} code={}",
                        event != null ? event.orderId() : null, topic, partition, offset, ex.getCode());
                return;
            }
            log.warn("[OrderCreatedEventListener] Auto-assignment rejected orderId={} topic={} partition={} offset={} code={} message={}",
                    event != null ? event.orderId() : null, topic, partition, offset, ex.getCode(), ex.getMessage());
        } catch (Exception e) {
            log.error("[OrderCreatedEventListener] Failed to consume order event topic={} key={} partition={} offset={} eventType={} orderId={} exception={} message={}",
                    topic, key, partition, offset, event != null ? event.eventType() : null,
                    event != null ? event.orderId() : null, e.getClass().getSimpleName(), e.getMessage(), e);
            throw new IllegalStateException("Failed to consume order-created event", e);
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
            String status,
            String serviceType,
            String parcelSize,
            OffsetDateTime createdAt
    ) {}
}
