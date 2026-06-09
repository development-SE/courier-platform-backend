package kz.courier.logisticsservice.entity;

import lombok.Getter;

@Getter
public enum AssignmentStatus {

    PENDING("Waiting for courier decision"),
    ASSIGNED("Assigned to courier"),
    MANUAL_REQUIRED("Manual assignment required"),

    ACCEPTED("Courier accepted the order"),
    REJECTED("Courier rejected the order"),
    TIMED_OUT("Courier offer timed out"),

    PICKED_UP("Picked up"),
    IN_TRANSIT("In transit"),
    ARRIVED("Courier arrived"),

    DELIVERED("Delivered"),
    CANCELLED("Cancelled"),
    FAILED("Failed");

    private final String description;

    AssignmentStatus(String description) {
        this.description = description;
    }

    public boolean isTerminal() {
        return this == DELIVERED
                || this == CANCELLED
                || this == FAILED
                || this == MANUAL_REQUIRED
                || this == TIMED_OUT;
    }

    public boolean isActive() {
        return !isTerminal() && this != REJECTED;
    }

    public boolean canTransitionTo(AssignmentStatus newStatus) {
        if (isTerminal()) {
            return false;
        }

        return switch (this) {
            case PENDING -> newStatus == ASSIGNED
                    || newStatus == ACCEPTED
                    || newStatus == REJECTED
                    || newStatus == TIMED_OUT
                    || newStatus == CANCELLED;
            case ASSIGNED -> newStatus == ACCEPTED
                    || newStatus == REJECTED
                    || newStatus == CANCELLED;
            case ACCEPTED -> newStatus == PICKED_UP || newStatus == CANCELLED;
            case PICKED_UP -> newStatus == IN_TRANSIT;
            case IN_TRANSIT -> newStatus == ARRIVED || newStatus == FAILED;
            case ARRIVED -> newStatus == DELIVERED || newStatus == FAILED;
            case REJECTED, TIMED_OUT, DELIVERED, CANCELLED, FAILED, MANUAL_REQUIRED -> false;
        };
    }
}
