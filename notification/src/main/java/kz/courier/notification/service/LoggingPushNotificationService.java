package kz.courier.notification.service;

import kz.courier.notification.model.DeviceToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Locale;

import kz.courier.notification.service.PushNotificationService.PushSendResult;

@Slf4j
@Service
@ConditionalOnProperty(name = "firebase.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingPushNotificationService implements PushProviderSender {

    @Override
    public boolean supports(String provider) {
        return "FCM".equals(provider == null ? "" : provider.toUpperCase(Locale.ROOT));
    }

    @Override
    public PushSendResult send(DeviceToken token, PushMessage message) {
        log.info("Push notification simulated userId={} deviceId={} title={}",
                token.getUserId(), token.getDeviceId(), message.title());
        return PushSendResult.ok();
    }
}
