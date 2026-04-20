package kz.courier.notification.service;

import kz.courier.notification.model.DeviceToken;

/**
 * Provider-specific push sender.
 *
 * <p>The application-level {@link PushNotificationService} routes a stored
 * device token to one of these implementations based on DeviceToken.provider.
 * This keeps Expo, Firebase, and future APNs implementations isolated.</p>
 */
public interface PushProviderSender {

    boolean supports(String provider);

    PushNotificationService.PushSendResult send(DeviceToken token, PushMessage message);
}
