package kz.courier.orderservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import kz.courier.order.v1.GetOrderRequest;
import kz.courier.order.v1.GetOrderResponse;
import kz.courier.order.v1.ListOrdersRequest;
import kz.courier.order.v1.ListOrdersResponse;
import kz.courier.order.v1.UpdateOrderStatusRequest;
import kz.courier.order.v1.UpdateOrderStatusResponse;
import kz.courier.orderservice.model.Order;
import kz.courier.orderservice.model.OrderStatus;
import kz.courier.orderservice.model.ServiceType;
import kz.courier.orderservice.kafka.OrderEventPublisher;
import kz.courier.orderservice.repository.AddressRepository;
import kz.courier.orderservice.repository.ContactRepository;
import kz.courier.orderservice.repository.OrderRepository;
import kz.courier.orderservice.security.AuthenticatedUser;
import kz.courier.orderservice.security.GrpcAuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderGrpcServiceAuthorizationTests {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private AddressRepository addressRepository;

    @Mock
    private ContactRepository contactRepository;

    @Mock
    private DeliveryConfirmationService deliveryConfirmationService;

    @Mock
    private OrderEventPublisher orderEventPublisher;

    private OrderGrpcService service;

    @BeforeEach
    void setUp() {
        service = new OrderGrpcService(
                orderRepository,
                addressRepository,
                contactRepository,
                new ObjectMapper(),
                deliveryConfirmationService,
                orderEventPublisher
        );
    }

    @Test
    void getOrderRejectsForeignOrderForRegularUser() {
        UUID callerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        when(orderRepository.findById(orderId))
                .thenReturn(Optional.of(order(orderId, UUID.randomUUID(), OrderStatus.NEW)));

        RecordingObserver<GetOrderResponse> observer = new RecordingObserver<>();
        runAs(new AuthenticatedUser(callerId.toString(), List.of("CLIENT"), null),
                () -> service.getOrder(GetOrderRequest.newBuilder()
                        .setOrderId(orderId.toString())
                        .build(), observer));

        assertTrue(observer.completed);
        assertFalse(observer.value.getResponse().getSuccess());
        assertEquals("FORBIDDEN", observer.value.getResponse().getError().getCode());
    }

    @Test
    void getOrderAllowsForeignOrderForCourier() {
        UUID callerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        when(orderRepository.findById(orderId))
                .thenReturn(Optional.of(fullyPopulatedOrder(orderId, UUID.randomUUID(), OrderStatus.NEW)));

        RecordingObserver<GetOrderResponse> observer = new RecordingObserver<>();
        runAs(new AuthenticatedUser(callerId.toString(), List.of("COURIER"), null),
                () -> service.getOrder(GetOrderRequest.newBuilder()
                        .setOrderId(orderId.toString())
                        .build(), observer));

        assertTrue(observer.completed);
        assertTrue(observer.value.getResponse().getSuccess());
        assertEquals(orderId.toString(), observer.value.getOrderId());
    }

    @Test
    void updateOrderStatusAllowsOnlyCancelForRegularOwner() {
        UUID callerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        when(orderRepository.findById(orderId))
                .thenReturn(Optional.of(order(orderId, callerId, OrderStatus.NEW)));

        RecordingObserver<UpdateOrderStatusResponse> observer = new RecordingObserver<>();
        runAs(new AuthenticatedUser(callerId.toString(), List.of("CLIENT"), null),
                () -> service.updateOrderStatus(UpdateOrderStatusRequest.newBuilder()
                        .setOrderId(orderId.toString())
                        .setNewStatus(kz.courier.order.v1.OrderStatus.ACCEPTED)
                        .build(), observer));

        assertTrue(observer.completed);
        assertFalse(observer.value.getResponse().getSuccess());
        assertEquals("FORBIDDEN", observer.value.getResponse().getError().getCode());
    }

    @Test
    void listOrdersRejectsForeignClientFilterForRegularUser() {
        UUID callerId = UUID.randomUUID();
        UUID requestedClientId = UUID.randomUUID();

        RecordingObserver<ListOrdersResponse> observer = new RecordingObserver<>();
        runAs(new AuthenticatedUser(callerId.toString(), List.of("CLIENT"), null),
                () -> service.listOrders(ListOrdersRequest.newBuilder()
                        .setClientId(requestedClientId.toString())
                        .build(), observer));

        assertTrue(observer.completed);
        assertFalse(observer.value.getResponse().getSuccess());
        assertEquals("FORBIDDEN", observer.value.getResponse().getError().getCode());
    }

    @Test
    void listOrdersAllowsPrivilegedCallerToQueryAllOrders() {
        UUID callerId = UUID.randomUUID();
        when(orderRepository.findAll(any(Specification.class), eq(PageRequest.of(0, 20,
                org.springframework.data.domain.Sort.by("createdAt").descending()))))
                .thenReturn(new PageImpl<>(List.of()));

        RecordingObserver<ListOrdersResponse> observer = new RecordingObserver<>();
        runAs(new AuthenticatedUser(callerId.toString(), List.of("ADMIN"), null),
                () -> service.listOrders(ListOrdersRequest.newBuilder().build(), observer));

        assertTrue(observer.completed);
        assertTrue(observer.value.getResponse().getSuccess());
        assertEquals(0, observer.value.getOrdersCount());
        verify(orderRepository).findAll(any(Specification.class), any(PageRequest.class));
    }

    @Test
    void listOrdersRejectsForeignCompanyFilterForCompanyScopedUser() {
        UUID callerId = UUID.randomUUID();
        UUID callerCompanyId = UUID.randomUUID();
        UUID requestedCompanyId = UUID.randomUUID();

        RecordingObserver<ListOrdersResponse> observer = new RecordingObserver<>();
        runAs(new AuthenticatedUser(callerId.toString(), List.of("DIRECTOR"), null, callerCompanyId.toString()),
                () -> service.listOrders(ListOrdersRequest.newBuilder()
                        .setCompanyId(requestedCompanyId.toString())
                        .build(), observer));

        assertTrue(observer.completed);
        assertFalse(observer.value.getResponse().getSuccess());
        assertEquals("FORBIDDEN", observer.value.getResponse().getError().getCode());
    }

    @Test
    void listOrdersRejectsInvalidAmountRange() {
        UUID callerId = UUID.randomUUID();

        RecordingObserver<ListOrdersResponse> observer = new RecordingObserver<>();
        runAs(new AuthenticatedUser(callerId.toString(), List.of("ADMIN"), null),
                () -> service.listOrders(ListOrdersRequest.newBuilder()
                        .setMinAmount(100)
                        .setMaxAmount(10)
                        .build(), observer));

        assertTrue(observer.completed);
        assertFalse(observer.value.getResponse().getSuccess());
        assertEquals("INVALID_ARGUMENT", observer.value.getResponse().getError().getCode());
    }

    private void runAs(AuthenticatedUser user, Runnable action) {
        Context.current()
                .withValue(GrpcAuthContext.AUTHENTICATED_USER_KEY, user)
                .run(action);
    }

    private Order order(UUID orderId, UUID authorId, OrderStatus status) {
        return Order.builder()
                .id(orderId)
                .authorId(authorId)
                .serviceType(ServiceType.STANDARD)
                .itemsJson("[]")
                .status(status)
                .build();
    }

    private Order fullyPopulatedOrder(UUID orderId, UUID authorId, OrderStatus status) {
        kz.courier.orderservice.model.Address address = kz.courier.orderservice.model.Address.builder()
                .id(UUID.randomUUID())
                .type(kz.courier.orderservice.model.AddressType.USER)
                .city("Almaty")
                .street("Abay")
                .house("10")
                .latitude(43.2)
                .longitude(76.9)
                .build();

        kz.courier.orderservice.model.Contact contact = kz.courier.orderservice.model.Contact.builder()
                .id(UUID.randomUUID())
                .name("John")
                .phone("+77071234567")
                .build();

        return Order.builder()
                .id(orderId)
                .authorId(authorId)
                .serviceType(ServiceType.STANDARD)
                .itemsJson("[]")
                .status(status)
                .deliveryAddress(address)
                .pickupAddress(address)
                .recipientContact(contact)
                .pickupContact(contact)
                .build();
    }

    private static final class RecordingObserver<T> implements StreamObserver<T> {
        private T value;
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(T value) {
            this.value = value;
        }

        @Override
        public void onError(Throwable t) {
            this.error = t;
        }

        @Override
        public void onCompleted() {
            this.completed = true;
        }
    }
}
