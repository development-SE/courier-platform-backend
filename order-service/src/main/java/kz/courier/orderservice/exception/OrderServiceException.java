package kz.courier.orderservice.exception;

/**
 * Generic domain exception for order-service business rule violations.
 */
public class OrderServiceException extends RuntimeException {

    private final String code;

    public OrderServiceException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
