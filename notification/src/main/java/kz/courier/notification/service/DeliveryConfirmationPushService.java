package kz.courier.notification.service;

import kz.courier.notification.dto.NotificationEvent;
import kz.courier.notification.model.DeviceToken;
import kz.courier.notification.repository.DeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryConfirmationPushService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final PushNotificationService pushNotificationService;

    @Transactional
    public void sendDeliveryConfirmationCode(NotificationEvent event) {
        UUID userId = UUID.fromString(event.getUserId());
        List<DeviceToken> tokens = deviceTokenRepository.findAllByUserIdAndEnabledTrueAndRevokedAtIsNull(userId);
        if (tokens.isEmpty()) {
            log.info("No active push tokens for delivery confirmation userId={} eventId={}",
                    userId, event.getEventId());
            return;
        }

        Map<String, Object> payload = event.getPayload();
        String orderId = requireString(payload, "order_id");
        String code = requireString(payload, "code");
        String expiresAt = requireString(payload, "expires_at");

        PushMessage message = new PushMessage(
                "Delivery confirmation code",
                "Your delivery code is " + code,
                Map.of(
                        "type", event.getType(),
                        "order_id", orderId,
                        "expires_at", expiresAt
                ));

        for (DeviceToken token : tokens) {
            PushNotificationService.PushSendResult result = pushNotificationService.send(token, message);
            if (result.invalidToken()) {
                token.setEnabled(false);
                token.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC));
                deviceTokenRepository.save(token);
            }
        }

        log.info("Delivery confirmation push processed userId={} orderId={} devices={}",
                userId, orderId, tokens.size());
    }

    private String requireString(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing payload key: " + key);
        }
        return value.toString();
    }
}
