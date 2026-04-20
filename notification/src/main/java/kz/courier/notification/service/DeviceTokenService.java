package kz.courier.notification.service;

import kz.courier.notification.dto.DeviceTokenDto;
import kz.courier.notification.model.DeviceToken;
import kz.courier.notification.repository.DeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    private final DeviceTokenRepository repository;

    @Transactional
    public void register(UUID userId, DeviceTokenDto.RegisterDeviceRequest request) {
        String provider = request.provider().toUpperCase(Locale.ROOT);
        validateToken(provider, request.platform(), request.pushToken());

        String tokenHash = sha256(request.pushToken());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        repository.findByTokenHash(tokenHash)
                .filter(existing -> !existing.getUserId().equals(userId)
                        || !existing.getDeviceId().equals(request.deviceId()))
                .ifPresent(existing -> {
                    existing.setEnabled(false);
                    existing.setRevokedAt(now);
                    repository.save(existing);
                    log.info("Revoked duplicate push token owner userId={} deviceId={}",
                            existing.getUserId(), existing.getDeviceId());
                });

        DeviceToken token = repository.findByUserIdAndDeviceId(userId, request.deviceId())
                .orElseGet(() -> DeviceToken.builder()
                        .userId(userId)
                        .deviceId(request.deviceId())
                        .build());

        token.setPlatform(request.platform().toUpperCase(Locale.ROOT));
        token.setProvider(provider);
        token.setPushToken(request.pushToken());
        token.setTokenHash(tokenHash);
        token.setAppVersion(request.appVersion());
        token.setLocale(request.locale());
        token.setEnabled(true);
        token.setRevokedAt(null);
        token.setLastSeenAt(now);
        repository.save(token);

        log.info("Device token registered userId={} deviceId={} platform={} provider={}",
                userId, request.deviceId(), token.getPlatform(), token.getProvider());
    }

    @Transactional
    public void revoke(UUID userId, String deviceId) {
        repository.findByUserIdAndDeviceId(userId, deviceId)
                .ifPresent(token -> {
                    token.setEnabled(false);
                    token.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    repository.save(token);
                    log.info("Device token revoked userId={} deviceId={}", userId, deviceId);
                });
    }

    @Transactional
    public void updateEnabled(UUID userId, String deviceId, boolean enabled) {
        DeviceToken token = repository.findByUserIdAndDeviceId(userId, deviceId)
                .orElseThrow(() -> new IllegalArgumentException("Device token not found"));
        token.setEnabled(enabled);
        token.setRevokedAt(enabled ? null : OffsetDateTime.now(ZoneOffset.UTC));
        token.setLastSeenAt(OffsetDateTime.now(ZoneOffset.UTC));
        repository.save(token);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash push token", e);
        }
    }

    private void validateToken(String provider, String platform, String pushToken) {
        if ("EXPO".equals(provider) && !isExpoToken(pushToken)) {
            throw new IllegalArgumentException("Expo provider requires ExpoPushToken[...] or ExponentPushToken[...]");
        }

        boolean iosApnsToken = "IOS".equalsIgnoreCase(platform)
                && pushToken != null
                && pushToken.matches("^[a-fA-F0-9]{64}$");
        if ("FCM".equals(provider) && iosApnsToken) {
            throw new IllegalArgumentException(
                    "This looks like an iOS APNs token, not an FCM token. Use provider EXPO with an ExpoPushToken, or send a real Firebase FCM token.");
        }
    }

    private boolean isExpoToken(String token) {
        return token != null
                && (token.startsWith("ExpoPushToken[") || token.startsWith("ExponentPushToken["));
    }
}
