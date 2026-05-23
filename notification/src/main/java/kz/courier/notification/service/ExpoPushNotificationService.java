package kz.courier.notification.service;

import kz.courier.notification.model.DeviceToken;
import kz.courier.notification.service.PushNotificationService.PushSendResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sends push notifications through Expo Push API.
 *
 * <p>Expo mobile apps usually expose tokens like {@code ExponentPushToken[...] }
 * or {@code ExpoPushToken[...]}. These tokens are not valid Firebase tokens,
 * therefore they must be sent to Expo instead of Firebase Admin SDK.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpoPushNotificationService implements PushProviderSender {

    private final RestClient.Builder restClientBuilder;

    @Value("${expo.push.url:https://exp.host/--/api/v2/push/send}")
    private String expoPushUrl;

    @Override
    public boolean supports(String provider) {
        return "EXPO".equals(provider == null ? "" : provider.toUpperCase(Locale.ROOT));
    }

    @Override
    public PushSendResult send(DeviceToken token, PushMessage message) {
        if (!looksLikeExpoToken(token.getPushToken())) {
            log.warn("Expo push token has invalid format userId={} deviceId={}",
                    token.getUserId(), token.getDeviceId());
            return PushSendResult.invalid();
        }

        Map<String, Object> request = Map.of(
                "to", token.getPushToken(),
                "title", message.title(),
                "body", message.body(),
                "sound", "default",
                "data", message.data()
        );

        try {
            ExpoPushResponse response = restClientBuilder.build()
                    .post()
                    .uri(expoPushUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(ExpoPushResponse.class);

            return classifyResponse(response, token);
        } catch (RestClientException ex) {
            log.warn("Expo push request failed userId={} deviceId={} message={}",
                    token.getUserId(), token.getDeviceId(), ex.getMessage());
            return PushSendResult.failed();
        }
    }

    private PushSendResult classifyResponse(ExpoPushResponse response, DeviceToken token) {
        if (response == null || response.data() == null || response.data().isEmpty()) {
            return PushSendResult.failed();
        }

        ExpoPushTicket ticket = response.data().getFirst();
        if ("ok".equalsIgnoreCase(ticket.status())) {
            return PushSendResult.ok();
        }

        String error = ticket.details() == null ? "" : String.valueOf(ticket.details().get("error"));
        log.warn("Expo push rejected userId={} deviceId={} status={} error={}",
                token.getUserId(), token.getDeviceId(), ticket.status(), error);

        if ("DeviceNotRegistered".equals(error) || "InvalidCredentials".equals(error)) {
            return PushSendResult.invalid();
        }
        return PushSendResult.failed();
    }

    private boolean looksLikeExpoToken(String token) {
        return token != null
                && (token.startsWith("ExponentPushToken[") || token.startsWith("ExpoPushToken["));
    }

    private record ExpoPushResponse(List<ExpoPushTicket> data) {
    }

    private record ExpoPushTicket(String status, String id, String message, Map<String, Object> details) {
    }
}
