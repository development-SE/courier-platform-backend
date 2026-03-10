package kz.courier.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Mirrors kz.courier.authservice.dto.NotificationEvent produced by auth-service.
 *
 * Supported types:
 *  - email_verification
 *  - password_changed
 *  - account_deleted
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEvent {

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("user_id")
    private String userId;

    /** Discriminator: email_verification | password_changed | account_deleted */
    @JsonProperty("type")
    private String type;

    /**
     * Flexible payload — keys depend on the event type.
     *
     * email_verification → { user_name, verify_link }
     * password_changed   → { user_name, email, changed_at }
     * account_deleted    → { user_name, email, deleted_at }
     */
    @JsonProperty("payload")
    private Map<String, Object> payload;

    @JsonProperty("created_at")
    private Instant createdAt;
}