package kz.courier.authservice.service;


import kz.courier.authservice.dto.NotificationEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${notification-kafka-topic}")
    private String topic;

    public void publish(NotificationEvent event) {
        kafkaTemplate.send(topic, event.getUserId(), event);
    }
}
