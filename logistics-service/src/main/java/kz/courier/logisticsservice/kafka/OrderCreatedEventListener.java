package kz.courier.logisticsservice.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.courier.logisticsservice.exception.BusinessException;
import kz.courier.logisticsservice.security.SystemPrincipalRunner;
import kz.courier.logisticsservice.service.CapacityAwareAssignmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @KafkaListener(
            topics = "${kafka.topic.order-events:kafka-order-events}",
            groupId = "${spring.kafka.consumer.group-id:logistics-service}")
    public void onOrderCreated(String payload) {
        try {
            OrderCreatedEvent event = objectMapper.readValue(payload, OrderCreatedEvent.class);
            if (!Set.of("ORDER_CREATED", "ORDER_READY", "ORDER_STATUS_CHANGED").contains(event.eventType())) {
                log.debug("[OrderCreatedEventListener] Ignoring eventType={}", event.eventType());
                return;
            }
            if (event.status() == null || !ASSIGNMENT_ELIGIBLE_ORDER_STATUSES.contains(event.status())) {
                log.debug("[OrderCreatedEventListener] Ignoring eventType={} status={} for orderId={}",
                        event.eventType(), event.status(), event.orderId());
                return;
            }
            if (assignmentService.hasActiveAssignment(event.orderId())) {
                log.info("[OrderCreatedEventListener] Order already has active assignment orderId={}",
                        event.orderId());
                return;
            }

            systemPrincipalRunner.run(() -> assignmentService.autoAssign(event.orderId()));
            log.info("[OrderCreatedEventListener] Auto-assignment triggered orderId={}", event.orderId());
        } catch (BusinessException ex) {
            if ("DUPLICATE_ASSIGNMENT".equals(ex.getCode())) {
                log.info("[OrderCreatedEventListener] Duplicate assignment ignored: {}", ex.getMessage());
                return;
            }
            log.warn("[OrderCreatedEventListener] Auto-assignment rejected: {}", ex.getMessage());
        } catch (Exception e) {
            log.error("[OrderCreatedEventListener] Failed to consume order-created payload={}", payload, e);
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
