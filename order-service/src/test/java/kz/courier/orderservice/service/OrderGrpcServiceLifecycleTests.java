package kz.courier.orderservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Context;
import kz.courier.order.v1.CreateOrderRequest;
import kz.courier.order.v1.CreateOrderResponse;
import kz.courier.order.v1.UpdateOrderStatusRequest;
import kz.courier.order.v1.UpdateOrderStatusResponse;
import kz.courier.orderservice.kafka.OrderEventPublisher;
import kz.courier.orderservice.model.Address;
import kz.courier.orderservice.model.Contact;
import kz.courier.orderservice.model.Order;
import kz.courier.orderservice.model.OrderStatus;
import kz.courier.orderservice.model.ServiceType;
import kz.courier.orderservice.repository.AddressRepository;
import kz.courier.orderservice.repository.ContactRepository;
import kz.courier.orderservice.repository.OrderRepository;
import kz.courier.orderservice.security.AuthenticatedUser;
import kz.courier.orderservice.security.GrpcAuthContext;
import kz.courier.orderservice.support.RecordingObserver;
import kz.courier.orderservice.support.TestOrders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderGrpcServiceLifecycleTests {

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
    void should_CreateOrderWithReadyStatus_When_CompanyIdIsNull() {
        UUID callerId = UUID.randomUUID();
        CreateOrderRequest request = TestOrders.readyCustomOrder();
        when(addressRepository.save(any(Address.class))).thenAnswer(invocation -> withAddressId(invocation.getArgument(0)));
        when(contactRepository.save(any(Contact.class))).thenAnswer(invocation -> withContactId(invocation.getArgument(0)));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> withOrderId(invocation.getArgument(0)));

        RecordingObserver<CreateOrderResponse> observer = new RecordingObserver<>();
        runAs(new AuthenticatedUser(callerId.toString(), List.of("CLIENT"), null),
                () -> service.createOrder(request, observer));

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        Order savedOrder = orderCaptor.getValue();

        assertThat(observer.completed()).isTrue();
        assertThat(observer.value().getResponse().getSuccess()).isTrue();
        assertThat(observer.value().getCurrentStatus()).isEqualTo(kz.courier.order.v1.OrderStatus.READY);
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.READY);
        assertThat(savedOrder.getCompanyId()).isNull();
        verify(orderEventPublisher).publishCreatedAfterCommit(savedOrder);
        verify(orderEventPublisher).publishReadyAfterCommit(savedOrder);
    }

    @Test
    void should_CreateOrderWithNewStatus_When_CompanyIdIsPresent() {
        UUID callerId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        CreateOrderRequest request = TestOrders.newCompanyOrder(companyId.toString());
        when(addressRepository.save(any(Address.class))).thenAnswer(invocation -> withAddressId(invocation.getArgument(0)));
        when(contactRepository.save(any(Contact.class))).thenAnswer(invocation -> withContactId(invocation.getArgument(0)));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> withOrderId(invocation.getArgument(0)));

        RecordingObserver<CreateOrderResponse> observer = new RecordingObserver<>();
        runAs(new AuthenticatedUser(callerId.toString(), List.of("DIRECTOR"), null, companyId.toString()),
                () -> service.createOrder(request, observer));

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        Order savedOrder = orderCaptor.getValue();

        assertThat(observer.completed()).isTrue();
        assertThat(observer.value().getResponse().getSuccess()).isTrue();
        assertThat(observer.value().getCurrentStatus()).isEqualTo(kz.courier.order.v1.OrderStatus.NEW);
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(savedOrder.getCompanyId()).isEqualTo(companyId);
        verify(orderEventPublisher).publishCreatedAfterCommit(savedOrder);
        verify(orderEventPublisher, never()).publishReadyAfterCommit(any(Order.class));
    }

    @Test
    void should_AllowCompanyRoleToMoveCompanyOrderToReady_When_SameCompany() {
        UUID companyId = UUID.randomUUID();
        Order order = companyOrder(companyId, OrderStatus.ACCEPTED);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        RecordingObserver<UpdateOrderStatusResponse> observer = new RecordingObserver<>();
        runAs(new AuthenticatedUser(UUID.randomUUID().toString(), List.of("MANAGER"), null, companyId.toString()),
                () -> service.updateOrderStatus(UpdateOrderStatusRequest.newBuilder()
                        .setOrderId(order.getId().toString())
                        .setNewStatus(kz.courier.order.v1.OrderStatus.READY)
                        .build(), observer));

        assertThat(observer.completed()).isTrue();
        assertThat(observer.value().getResponse().getSuccess()).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.READY);
        verify(orderEventPublisher).publishStatusChangedAfterCommit(order);
        verify(orderEventPublisher).publishReadyAfterCommit(order);
    }

    @Test
    void should_AllowAdminToMoveCompanyOrderToReady() {
        UUID companyId = UUID.randomUUID();
        Order order = companyOrder(companyId, OrderStatus.ACCEPTED);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        RecordingObserver<UpdateOrderStatusResponse> observer = new RecordingObserver<>();
        runAs(new AuthenticatedUser(UUID.randomUUID().toString(), List.of("ADMIN"), null),
                () -> service.updateOrderStatus(UpdateOrderStatusRequest.newBuilder()
                        .setOrderId(order.getId().toString())
                        .setNewStatus(kz.courier.order.v1.OrderStatus.READY)
                        .build(), observer));

        assertThat(observer.completed()).isTrue();
        assertThat(observer.value().getResponse().getSuccess()).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.READY);
    }

    @Test
    void should_RejectClientMovingCompanyOrderToReady() {
        UUID callerId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        Order order = companyOrder(companyId, OrderStatus.ACCEPTED);
        order.setAuthorId(callerId);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        RecordingObserver<UpdateOrderStatusResponse> observer = new RecordingObserver<>();
        runAs(new AuthenticatedUser(callerId.toString(), List.of("CLIENT"), null),
                () -> service.updateOrderStatus(UpdateOrderStatusRequest.newBuilder()
                        .setOrderId(order.getId().toString())
                        .setNewStatus(kz.courier.order.v1.OrderStatus.READY)
                        .build(), observer));

        assertThat(observer.completed()).isTrue();
        assertThat(observer.value().getResponse().getSuccess()).isFalse();
        assertThat(observer.value().getResponse().getError().getCode()).isEqualTo("FORBIDDEN");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ACCEPTED);
    }

    @Test
    void should_RejectCompanyOrderUpdate_When_ManagerBelongsToDifferentCompany() {
        UUID orderCompanyId = UUID.randomUUID();
        UUID callerCompanyId = UUID.randomUUID();
        Order order = companyOrder(orderCompanyId, OrderStatus.ACCEPTED);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        RecordingObserver<UpdateOrderStatusResponse> observer = new RecordingObserver<>();
        runAs(new AuthenticatedUser(UUID.randomUUID().toString(), List.of("MANAGER"), null, callerCompanyId.toString()),
                () -> service.updateOrderStatus(UpdateOrderStatusRequest.newBuilder()
                        .setOrderId(order.getId().toString())
                        .setNewStatus(kz.courier.order.v1.OrderStatus.READY)
                        .build(), observer));

        assertThat(observer.completed()).isTrue();
        assertThat(observer.value().getResponse().getSuccess()).isFalse();
        assertThat(observer.value().getResponse().getError().getCode()).isEqualTo("FORBIDDEN");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ACCEPTED);
    }

    @Test
    void should_RejectInvalidStatusTransition_When_TransitionNotAllowed() {
        UUID companyId = UUID.randomUUID();
        Order order = companyOrder(companyId, OrderStatus.NEW);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        RecordingObserver<UpdateOrderStatusResponse> observer = new RecordingObserver<>();
        runAs(new AuthenticatedUser(UUID.randomUUID().toString(), List.of("DIRECTOR"), null, companyId.toString()),
                () -> service.updateOrderStatus(UpdateOrderStatusRequest.newBuilder()
                        .setOrderId(order.getId().toString())
                        .setNewStatus(kz.courier.order.v1.OrderStatus.READY)
                        .build(), observer));

        assertThat(observer.completed()).isTrue();
        assertThat(observer.value().getResponse().getSuccess()).isFalse();
        assertThat(observer.value().getResponse().getError().getCode()).isEqualTo("INVALID_TRANSITION");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
        verify(orderEventPublisher, never()).publishStatusChangedAfterCommit(any(Order.class));
    }

    private void runAs(AuthenticatedUser user, Runnable action) {
        Context.current()
                .withValue(GrpcAuthContext.AUTHENTICATED_USER_KEY, user)
                .run(action);
    }

    private Address withAddressId(Address address) {
        if (address.getId() == null) {
            address.setId(UUID.randomUUID());
        }
        if (address.getCreatedAt() == null) {
            address.setCreatedAt(OffsetDateTime.now());
        }
        if (address.getUpdatedAt() == null) {
            address.setUpdatedAt(OffsetDateTime.now());
        }
        return address;
    }

    private Contact withContactId(Contact contact) {
        if (contact.getId() == null) {
            contact.setId(UUID.randomUUID());
        }
        if (contact.getCreatedAt() == null) {
            contact.setCreatedAt(OffsetDateTime.now());
        }
        if (contact.getUpdatedAt() == null) {
            contact.setUpdatedAt(OffsetDateTime.now());
        }
        return contact;
    }

    private Order withOrderId(Order order) {
        if (order.getId() == null) {
            order.setId(UUID.randomUUID());
        }
        if (order.getCreatedAt() == null) {
            order.setCreatedAt(OffsetDateTime.now());
        }
        if (order.getUpdatedAt() == null) {
            order.setUpdatedAt(OffsetDateTime.now());
        }
        return order;
    }

    private Order companyOrder(UUID companyId, OrderStatus status) {
        return Order.builder()
                .id(UUID.randomUUID())
                .authorId(UUID.randomUUID())
                .companyId(companyId)
                .serviceType(ServiceType.STANDARD)
                .deliveryAddress(Address.builder()
                        .id(UUID.randomUUID())
                        .latitude(43.238949)
                        .longitude(76.889709)
                        .city("Almaty")
                        .street("Delivery Street")
                        .house("10")
                        .type(kz.courier.orderservice.model.AddressType.USER)
                        .build())
                .pickupAddress(Address.builder()
                        .id(UUID.randomUUID())
                        .latitude(43.25)
                        .longitude(76.9)
                        .city("Almaty")
                        .street("Pickup Street")
                        .house("20")
                        .type(kz.courier.orderservice.model.AddressType.COMPANY)
                        .build())
                .recipientContact(Contact.builder()
                        .id(UUID.randomUUID())
                        .name("Client")
                        .surname("Receiver")
                        .phone("+77010000001")
                        .build())
                .pickupContact(Contact.builder()
                        .id(UUID.randomUUID())
                        .name("Store")
                        .surname("Manager")
                        .phone("+77010000002")
                        .build())
                .itemsJson("[]")
                .status(status)
                .parcelSize(kz.courier.orderservice.model.ParcelSize.MEDIUM)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }
}
