package kz.courier.orderservice.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.courier.orderservice.exception.OrderNotFoundException;
import kz.courier.orderservice.model.Order;
import kz.courier.orderservice.model.OrderStatus;
import kz.courier.orderservice.repository.OrderRepository;
import kz.courier.orderservice.service.DeliveryConfirmationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Keeps order-service synchronized with assignment lifecycle events emitted by
 * logistics-service.
 *
 * <p>Logistics owns courier assignment state. Order-service consumes those
 * events to update the customer-facing order lifecycle and to generate the OTP
 * when the assigned courier arrives at the customer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssignmentEventConsumer {

    private static final String TOPIC_CREATED = "logistics.assignment.created";
    private static final String TOPIC_UPDATED = "logistics.assignment.updated";

    private final ObjectMapper objectMapper;
    private final OrderRepository orderRepository;
    private final DeliveryConfirmationService deliveryConfirmationService;

    @KafkaListener(topics = TOPIC_CREATED, groupId = "order-service")
    @Transactional
    public void onAssignmentCreated(String payload) {
        try {
            AssignmentCreatedEvent event =
                    objectMapper.readValue(payload, AssignmentCreatedEvent.class);

            Order order = orderRepository.findById(event.orderId())
                    .orElseThrow(() -> new OrderNotFoundException(event.orderId().toString()));

            OrderStatus mappedStatus = mapAssignmentStatus(
                    event.assignmentStatus() != null ? event.assignmentStatus() : AssignmentStatus.ASSIGNED);
            if (mappedStatus != null && !isTerminal(order.getStatus())) {
                order.setStatus(mappedStatus);
            }
            orderRepository.save(order);

            log.info("[AssignmentConsumer] Assignment created synced orderId={} courierId={} status={}",
                    order.getId(), event.courierId(), order.getStatus());
        } catch (Exception e) {
            log.error("[AssignmentConsumer] Failed to consume assignment created payload={}", payload, e);
            throw new IllegalStateException("Failed to consume assignment created event", e);
        }
    }

    @KafkaListener(topics = TOPIC_UPDATED, groupId = "order-service")
    @Transactional
    public void onAssignmentUpdated(String payload) {
        try {
            AssignmentStatusChangedEvent event =
                    objectMapper.readValue(payload, AssignmentStatusChangedEvent.class);

            Order order = orderRepository.findById(event.orderId())
                    .orElseThrow(() -> new OrderNotFoundException(event.orderId().toString()));

            OrderStatus mappedStatus = mapAssignmentStatus(event.newStatus());
            if (mappedStatus != null && !isTerminal(order.getStatus())) {
                order.setStatus(mappedStatus);
                orderRepository.save(order);
            }

            if (event.newStatus() == AssignmentStatus.ARRIVED) {
                deliveryConfirmationService.confirmArrivalFromAssignment(
                        event.orderId(), event.courierId());
            }

            log.info("[AssignmentConsumer] Assignment status synced orderId={} assignmentStatus={} orderStatus={}",
                    order.getId(), event.newStatus(), order.getStatus());


        } catch (Exception e) {
            log.error("[AssignmentConsumer] Failed to consume assignment updated payload={}", payload, e);
            throw new IllegalStateException("Failed to consume assignment updated event", e);
        }
    }

    private OrderStatus mapAssignmentStatus(AssignmentStatus status) {
        return switch (status) {
            case PENDING, TIMED_OUT, MANUAL_REQUIRED -> OrderStatus.ASSIGNMENT_PENDING;
            case ASSIGNED, ACCEPTED -> OrderStatus.ASSIGNED;
            case PICKED_UP -> OrderStatus.PICKED_UP;
            case IN_TRANSIT -> OrderStatus.IN_TRANSIT;
            case ARRIVED -> OrderStatus.DELIVERY_CONFIRMATION_PENDING;
            case CANCELLED, FAILED -> OrderStatus.CANCELLED;
            case REJECTED, DELIVERED -> null;
        };
    }

    private boolean isTerminal(OrderStatus status) {
        return status == OrderStatus.DELIVERED
                || status == OrderStatus.CANCELLED
                || status == OrderStatus.REJECTED;
    }

    private enum AssignmentStatus {
        PENDING,
        ASSIGNED,
        MANUAL_REQUIRED,
        ACCEPTED,
        REJECTED,
        TIMED_OUT,
        PICKED_UP,
        IN_TRANSIT,
        ARRIVED,
        DELIVERED,
        CANCELLED,
        FAILED
    }

    private record AssignmentCreatedEvent(
            UUID assignmentId,
            UUID orderId,
            UUID courierId,
            AssignmentStatus assignmentStatus,
            OffsetDateTime occurredAt
    ) {
    }

    private record AssignmentStatusChangedEvent(
            UUID assignmentId,
            UUID orderId,
            UUID courierId,
            AssignmentStatus oldStatus,
            AssignmentStatus newStatus,
            OffsetDateTime occurredAt
    ) {
    }
}
