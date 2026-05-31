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
public class CourierOfferPushService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final PushNotificationService pushNotificationService;

    @Transactional
    public void sendCourierOffer(NotificationEvent event) {
        UUID courierId = UUID.fromString(event.getUserId());
        List<DeviceToken> tokens = deviceTokenRepository.findAllByUserIdAndEnabledTrueAndRevokedAtIsNull(courierId);
        if (tokens.isEmpty()) {
            log.info("No active push tokens for courier offer courierId={} eventId={}",
                    courierId, event.getEventId());
            return;
        }

        Map<String, Object> payload = event.getPayload();
        String assignmentId = requireString(payload, "assignment_id");
        String orderId = requireString(payload, "order_id");

        PushMessage message = new PushMessage(
                "New delivery offer",
                "You have a new delivery request. Accept it before the offer expires.",
                Map.of(
                        "type", event.getType(),
                        "assignment_id", assignmentId,
                        "order_id", orderId
                ));

        for (DeviceToken token : tokens) {
            PushNotificationService.PushSendResult result = pushNotificationService.send(token, message);
            if (result.invalidToken()) {
                token.setEnabled(false);
                token.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC));
                deviceTokenRepository.save(token);
            }
        }

        log.info("Courier offer push processed courierId={} assignmentId={} orderId={} devices={}",
                courierId, assignmentId, orderId, tokens.size());
    }

    private String requireString(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing payload key: " + key);
        }
        return value.toString();
    }
}
