package kz.courier.logisticsservice.entity;

public enum AssignmentFailureReason {
    NO_ONLINE_COURIERS,
    STALE_LOCATIONS,
    COURIER_SERVICE_UNAVAILABLE,
    MISSING_ORDER_COORDINATES,
    PARCEL_TOO_LARGE_FOR_ALL_VEHICLES,
    INVALID_ORDER_DATA,
    NO_CAPACITY_AVAILABLE,
    MAX_ACTIVE_ORDERS_REACHED,
    UNKNOWN;

    public boolean isTemporary() {
        return switch (this) {
            case NO_ONLINE_COURIERS,
                 STALE_LOCATIONS,
                 COURIER_SERVICE_UNAVAILABLE,
                 NO_CAPACITY_AVAILABLE,
                 MAX_ACTIVE_ORDERS_REACHED,
                 UNKNOWN -> true;
            case MISSING_ORDER_COORDINATES,
                 PARCEL_TOO_LARGE_FOR_ALL_VEHICLES,
                 INVALID_ORDER_DATA -> false;
        };
    }
}
