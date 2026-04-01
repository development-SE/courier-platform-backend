package kz.courier.logisticsservice.exception;

import java.util.UUID;

public class LocationNotFoundException extends RuntimeException {
    public LocationNotFoundException(UUID courierId) {
        super("Location not found for courier: " + courierId);
    }
}







