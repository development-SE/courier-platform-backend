package kz.courier.notification.service;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import kz.courier.notification.model.DeviceToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.Locale;

import kz.courier.notification.service.PushNotificationService.PushSendResult;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBean(FirebaseApp.class)
public class FirebasePushNotificationService implements PushProviderSender {

    private final FirebaseApp firebaseApp;

    @Override
    public boolean supports(String provider) {
        return "FCM".equals(provider == null ? "" : provider.toUpperCase(Locale.ROOT));
    }

    @Override
    public PushSendResult send(DeviceToken token, PushMessage message) {
        Message firebaseMessage = Message.builder()
                .setToken(token.getPushToken())
                .putAllData(message.data())
                .putData("title", message.title())
                .putData("body", message.body())
                .build();

        try {
            FirebaseMessaging.getInstance(firebaseApp).send(firebaseMessage);
            return PushSendResult.ok();
        } catch (FirebaseMessagingException ex) {
            log.warn("Firebase push failed userId={} deviceId={} code={}",
                    token.getUserId(), token.getDeviceId(), ex.getMessagingErrorCode());
            String code = ex.getMessagingErrorCode() != null
                    ? ex.getMessagingErrorCode().name()
                    : "";
            if ("UNREGISTERED".equals(code) || "INVALID_ARGUMENT".equals(code)) {
                return PushSendResult.invalid();
            }
            return PushSendResult.failed();
        }
    }
}
