package kz.courier.logisticsservice.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.courier.logisticsservice.entity.AssignmentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes domain events to Kafka whenever an assignment transitions state.
 *
 * <p>Downstream consumers (order-service, notification-service, courier-service)
 * subscribe to these topics to react to logistics events without tight coupling.
 *
 * <p>Topics (configurable):
 * <ul>
 *   <li>{@code logistics.assignment.created}  — new assignment persisted</li>
 *   <li>{@code logistics.assignment.updated}  — status transition completed</li>
 *   <li>{@code logistics.courier.location}    — courier location updated</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssignmentEventPublisher {

    private static final String TOPIC_CREATED  = "logistics.assignment.created";
    private static final String TOPIC_UPDATED  = "logistics.assignment.updated";
    private static final String TOPIC_LOCATION = "logistics.courier.location";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${notification.kafka.topic:notification-kafka-topic}")
    private String notificationTopic;

    // ── Assignment created ────────────────────────────────────────────────────

    public void publishAssignmentCreated(UUID assignmentId, UUID orderId, UUID courierId) {
        publishAssignmentCreated(assignmentId, orderId, courierId, AssignmentStatus.ASSIGNED);
    }

    public void publishAssignmentCreated(UUID assignmentId, UUID orderId, UUID courierId,
                                         AssignmentStatus assignmentStatus) {
        publish(TOPIC_CREATED, assignmentId.toString(), new AssignmentCreatedEvent(
                assignmentId, orderId, courierId, assignmentStatus, OffsetDateTime.now()));
        publishCourierOfferNotification(assignmentId, orderId, courierId, assignmentStatus);
    }

    // ── Status transition ─────────────────────────────────────────────────────

    public void publishStatusChanged(UUID assignmentId, UUID orderId, UUID courierId,
                                     AssignmentStatus oldStatus, AssignmentStatus newStatus) {
        publish(TOPIC_UPDATED, assignmentId.toString(), new AssignmentStatusChangedEvent(
                assignmentId, orderId, courierId, oldStatus, newStatus, OffsetDateTime.now()));
    }

    // ── Location update ───────────────────────────────────────────────────────

    public void publishLocationUpdated(UUID courierId, double latitude, double longitude,
                                       boolean isOnline) {
        publish(TOPIC_LOCATION, courierId.toString(), new CourierLocationEvent(
                courierId, latitude, longitude, isOnline, OffsetDateTime.now()));
    }

    // ── Internal helper ───────────────────────────────────────────────────────

    private void publish(String topic, String key, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            kafkaTemplate.send(topic, key, json)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish to topic={} key={}: {}", topic, key, ex.getMessage());
                        } else {
                            log.debug("Published to topic={} key={} offset={}",
                                    topic, key, result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception e) {
            log.error("Serialization error publishing to {}: {}", topic, e.getMessage());
        }
    }

    private void publishCourierOfferNotification(UUID assignmentId, UUID orderId, UUID courierId,
                                                 AssignmentStatus assignmentStatus) {
        if (assignmentStatus != AssignmentStatus.PENDING || courierId == null) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assignment_id", assignmentId.toString());
        payload.put("order_id", orderId.toString());

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", UUID.randomUUID().toString());
        event.put("user_id", courierId.toString());
        event.put("type", "courier_assignment_offer_created");
        event.put("payload", payload);
        event.put("created_at", Instant.now().toString());

        publish(notificationTopic, courierId.toString(), event);
    }

    // ── Event records ─────────────────────────────────────────────────────────

    public record AssignmentCreatedEvent(
            UUID assignmentId,
            UUID orderId,
            UUID courierId,
            AssignmentStatus assignmentStatus,
            OffsetDateTime occurredAt
    ) {}

    public record AssignmentStatusChangedEvent(
            UUID assignmentId,
            UUID orderId,
            UUID courierId,
            AssignmentStatus oldStatus,
            AssignmentStatus newStatus,
            OffsetDateTime occurredAt
    ) {}

    public record CourierLocationEvent(
            UUID courierId,
            double latitude,
            double longitude,
            boolean isOnline,
            OffsetDateTime occurredAt
    ) {}
}
