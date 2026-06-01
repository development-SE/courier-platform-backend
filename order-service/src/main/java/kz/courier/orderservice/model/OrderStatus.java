package kz.courier.orderservice.model;

public enum OrderStatus {
    NEW,
    ACCEPTED,
    PREPARING,
    READY,
    ASSIGNMENT_PENDING,
    ASSIGNED,
    PICKED_UP,
    IN_TRANSIT,
    DELIVERY_CONFIRMATION_PENDING,
    DELIVERED,
    CANCELLED,
    REJECTED
}
