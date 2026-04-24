package kz.courier.courierservice.exception;

import java.util.UUID;

public class CourierNotFoundException extends RuntimeException {

    public CourierNotFoundException(UUID courierId) {
        super("Courier profile not found: " + courierId);
    }
}
