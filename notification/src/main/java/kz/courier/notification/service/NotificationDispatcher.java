package kz.courier.notification.service;

import kz.courier.notification.dto.NotificationEvent;
import kz.courier.notification.model.NotificationEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Central dispatcher: reads the event type and delegates to
 * the appropriate notification handler.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final EmailService emailService;
    private final DeliveryConfirmationPushService deliveryConfirmationPushService;

    public void dispatch(NotificationEvent event) {
        if (event.getType() == null || event.getType().isBlank()) {
            log.warn("Received event with null/blank type — skipping. eventId={}", event.getEventId());
            return;
        }

        try {
            NotificationEventType type = NotificationEventType.fromValue(event.getType());
            switch (type) {
                case EMAIL_VERIFICATION -> handleEmailVerification(event);
                case PASSWORD_CHANGED   -> handlePasswordChanged(event);
                case ACCOUNT_DELETED    -> handleAccountDeleted(event);
                case DELIVERY_CONFIRMATION_CODE_CREATED ->
                        deliveryConfirmationPushService.sendDeliveryConfirmationCode(event);
            }
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown event type '{}' — skipping. eventId={}", event.getType(), event.getEventId());
        }
    }

    /* ─────────────────────────── handlers ─────────────────────────────────── */

    private void handleEmailVerification(NotificationEvent event) {
        Map<String, Object> payload = event.getPayload();
        String to = requireString(payload, "email");

        emailService.sendHtml(
                to,
                "Confirm your email — Courier",
                "email-verification",
                Map.of(
                        "userName",  requireString(payload, "user_name"),
                        "verifyLink", requireString(payload, "verify_link")
                )
        );
        log.info("[email_verification] mail sent to {} (userId={})", to, event.getUserId());
    }

    private void handlePasswordChanged(NotificationEvent event) {
        Map<String, Object> payload = event.getPayload();
        String to = requireString(payload, "email");

        emailService.sendHtml(
                to,
                "Your password was changed — Courier",
                "password-changed",
                Map.of(
                        "userName",  requireString(payload, "user_name"),
                        "changedAt", requireString(payload, "changed_at")
                )
        );
        log.info("[password_changed] mail sent to {} (userId={})", to, event.getUserId());
    }

    private void handleAccountDeleted(NotificationEvent event) {
        Map<String, Object> payload = event.getPayload();
        String to = requireString(payload, "email");

        emailService.sendHtml(
                to,
                "Your account has been deleted — Courier",
                "account-deleted",
                Map.of(
                        "userName",  requireString(payload, "user_name"),
                        "deletedAt", requireString(payload, "deleted_at")
                )
        );
        log.info("[account_deleted] mail sent to {} (userId={})", to, event.getUserId());
    }

    /* ─────────────────────────── helpers ──────────────────────────────────── */

    private String requireString(Map<String, Object> payload, String key) {
        Object val = payload == null ? null : payload.get(key);
        if (val == null) throw new IllegalArgumentException("Missing payload key: " + key);
        return val.toString();
    }
}
