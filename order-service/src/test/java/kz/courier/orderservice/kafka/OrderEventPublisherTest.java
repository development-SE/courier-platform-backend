package kz.courier.orderservice.kafka;

import kz.courier.orderservice.model.Order;
import kz.courier.orderservice.model.OrderStatus;
import kz.courier.orderservice.model.ServiceType;
import kz.courier.orderservice.model.Address;
import kz.courier.orderservice.model.AddressType;
import kz.courier.orderservice.model.ParcelSize;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
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

    @Test
    void should_PublishOrderEventWithRequiredFields_When_StatusChangesToReady() {
        OrderEventPublisher publisher = new OrderEventPublisher(kafkaTemplate);
        ReflectionTestUtils.setField(publisher, "orderEventsTopic", "kafka-order-events");
        when(kafkaTemplate.send(eq("kafka-order-events"), anyString(), any()))
                .thenReturn(new CompletableFuture<>());

        UUID orderId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .authorId(clientId)
                .companyId(companyId)
                .serviceType(ServiceType.EXPRESS)
                .parcelSize(ParcelSize.LARGE)
                .status(OrderStatus.READY)
                .itemsJson("[]")
                .pickupAddress(Address.builder()
                        .id(UUID.randomUUID())
                        .type(AddressType.COMPANY)
                        .city("Almaty")
                        .street("Pickup Street")
                        .house("20")
                        .latitude(43.250000)
                        .longitude(76.900000)
                        .build())
                .deliveryAddress(Address.builder()
                        .id(UUID.randomUUID())
                        .type(AddressType.USER)
                        .city("Almaty")
                        .street("Delivery Street")
                        .house("10")
                        .latitude(43.238949)
                        .longitude(76.889709)
                        .build())
                .createdAt(OffsetDateTime.now())
                .build();

        publisher.publishReadyAfterCommit(order);

        ArgumentCaptor<OrderEventPublisher.OrderCreatedEvent> eventCaptor =
                ArgumentCaptor.forClass(OrderEventPublisher.OrderCreatedEvent.class);
        verify(kafkaTemplate).send(eq("kafka-order-events"), eq(orderId.toString()), eventCaptor.capture());

        OrderEventPublisher.OrderCreatedEvent event = eventCaptor.getValue();
        assert event != null;
        org.assertj.core.api.Assertions.assertThat(event.eventType()).isEqualTo("ORDER_READY");
        org.assertj.core.api.Assertions.assertThat(event.orderId()).isEqualTo(orderId);
        org.assertj.core.api.Assertions.assertThat(event.clientId()).isEqualTo(clientId);
        org.assertj.core.api.Assertions.assertThat(event.companyId()).isEqualTo(companyId);
        org.assertj.core.api.Assertions.assertThat(event.status()).isEqualTo("READY");
        org.assertj.core.api.Assertions.assertThat(event.serviceType()).isEqualTo("EXPRESS");
        org.assertj.core.api.Assertions.assertThat(event.parcelSize()).isEqualTo("LARGE");
        org.assertj.core.api.Assertions.assertThat(event.pickupLatitude()).isEqualTo(43.250000);
        org.assertj.core.api.Assertions.assertThat(event.pickupLongitude()).isEqualTo(76.900000);
        org.assertj.core.api.Assertions.assertThat(event.deliveryLatitude()).isEqualTo(43.238949);
        org.assertj.core.api.Assertions.assertThat(event.deliveryLongitude()).isEqualTo(76.889709);
        org.assertj.core.api.Assertions.assertThat(event.createdAt()).isEqualTo(order.getCreatedAt());
    }
}
