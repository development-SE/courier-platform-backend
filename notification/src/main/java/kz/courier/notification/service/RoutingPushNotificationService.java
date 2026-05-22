package kz.courier.notification.service;

import kz.courier.notification.model.DeviceToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class RoutingPushNotificationService implements PushNotificationService {

    private final List<PushProviderSender> senders;

    @Override
    public PushSendResult send(DeviceToken token, PushMessage message) {
        return senders.stream()
                .filter(sender -> sender.supports(token.getProvider()))
                .findFirst()
                .map(sender -> sender.send(token, message))
                .orElseGet(() -> {
                    log.warn("Unsupported push provider={} userId={} deviceId={}",
                            token.getProvider(), token.getUserId(), token.getDeviceId());
                    return PushSendResult.failed();
                });
    }
}
