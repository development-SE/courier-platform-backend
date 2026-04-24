package kz.courier.notification.model;

/**
 * All recognised notification event type strings.
 */
public enum NotificationEventType {

    EMAIL_VERIFICATION("email_verification"),
    PASSWORD_CHANGED("password_changed"),
    ACCOUNT_DELETED("account_deleted");

    private final String value;

    NotificationEventType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static NotificationEventType fromValue(String value) {
        for (NotificationEventType t : values()) {
            System.out.println("Comparing " + t.value + " with " + value);
            if (t.value.equalsIgnoreCase(value)) return t;
        }
        throw new IllegalArgumentException("Unknown notification type: " + value);
    }
}