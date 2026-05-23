package kz.courier.notification.service;

import kz.courier.notification.model.DeviceToken;

public interface PushNotificationService {

    PushSendResult send(DeviceToken token, PushMessage message);

    record PushSendResult(boolean success, boolean invalidToken) {
        public static PushSendResult ok() {
            return new PushSendResult(true, false);
        }

        public static PushSendResult failed() {
            return new PushSendResult(false, false);
        }

        public static PushSendResult invalid() {
            return new PushSendResult(false, true);
        }
    }
}
