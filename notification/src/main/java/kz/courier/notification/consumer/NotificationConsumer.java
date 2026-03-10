package kz.courier.notification.consumer;

import kz.courier.notification.dto.NotificationEvent;
import kz.courier.notification.service.NotificationDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final NotificationDispatcher dispatcher;

    /**
     * Main topic listener with automatic retry (3 attempts, 1 s back-off)
     * and dead-letter topic (DLT) fallback via @RetryableTopic.
     */
    
    @KafkaListener(
            topics = "${notification.kafka.topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(
            @Payload NotificationEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        log.info("Received event: type={} userId={} eventId={} topic={} offset={}",
                event.getType(), event.getUserId(), event.getEventId(), topic, offset);
        dispatcher.dispatch(event);
    }

    /**
     * Dead-letter topic handler — logs failed messages for manual inspection / alerting.
     */
    @KafkaListener(
            topics = "${notification.kafka.dead-letter-topic}",
            groupId = "${spring.kafka.consumer.group-id}-dlt"
    )
    public void consumeDeadLetter(
            @Payload NotificationEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        log.error("DLT message received — manual intervention required. " +
                        "eventId={} type={} userId={} topic={} offset={}",
                event.getEventId(), event.getType(), event.getUserId(), topic, offset);
        // TODO: persist to DB / alert on-call engineer / push to monitoring
    }
}