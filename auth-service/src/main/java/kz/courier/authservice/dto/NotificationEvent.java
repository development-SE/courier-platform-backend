package kz.courier.authservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data @Builder
public class NotificationEvent {
    @JsonProperty("event_id")
    private String eventId = UUID.randomUUID().toString();

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("type")
    private String type;               // e.g. "email_verification"

    @JsonProperty("payload")
    private Map<String, Object> payload;

    @JsonProperty("created_at")
    private Instant createdAt = Instant.now();
}
