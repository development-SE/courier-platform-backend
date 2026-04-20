package kz.courier.notification.model;

/**
 * All recognised notification event type strings.
 */
public enum NotificationEventType {

    EMAIL_VERIFICATION("email_verification"),
    PASSWORD_CHANGED("password_changed"),
    ACCOUNT_DELETED("account_deleted"),
    DELIVERY_CONFIRMATION_CODE_CREATED("delivery_confirmation_code_created");

    private final String value;

    NotificationEventType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static NotificationEventType fromValue(String value) {
        for (NotificationEventType t : values()) {
            if (t.value.equalsIgnoreCase(value)) return t;
        }
        throw new IllegalArgumentException("Unknown notification type: " + value);
    }
}
