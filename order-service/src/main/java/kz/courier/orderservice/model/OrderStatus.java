package kz.courier.orderservice.model;

public enum OrderStatus {
    NEW,
    ACCEPTED,
    PREPARING,
    READY,
    ASSIGNED,
    PICKED_UP,
    IN_TRANSIT,
    DELIVERED,
    CANCELLED,
    REJECTED
}
