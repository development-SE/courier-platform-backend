package kz.courier.orderservice.kafka;

import kz.courier.orderservice.model.Order;
import kz.courier.orderservice.model.OrderStatus;
import kz.courier.orderservice.model.ServiceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderEventPublisherTest {

    @Mock
    KafkaTemplate<String, Object> kafkaTemplate;

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesOrderCreatedOnlyAfterCommit() {
        OrderEventPublisher publisher = new OrderEventPublisher(kafkaTemplate);
        ReflectionTestUtils.setField(publisher, "orderEventsTopic", "kafka-order-events");
        when(kafkaTemplate.send(eq("kafka-order-events"), anyString(), any()))
                .thenReturn(new CompletableFuture<>());

        Order order = Order.builder()
                .id(UUID.randomUUID())
                .authorId(UUID.randomUUID())
                .serviceType(ServiceType.STANDARD)
                .itemsJson("[]")
                .status(OrderStatus.NEW)
                .build();

        TransactionSynchronizationManager.initSynchronization();
        publisher.publishCreatedAfterCommit(order);

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCommit());

        verify(kafkaTemplate).send(eq("kafka-order-events"), eq(order.getId().toString()), any());
    }
}
