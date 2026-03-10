package kz.courier.orderservice.exception;

/**
 * Thrown when a requested order cannot be found in the database.
 */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String orderId) {
        super("Order not found: " + orderId);
    }
}
