package kz.courier.notification.repository;

import kz.courier.notification.model.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    Optional<DeviceToken> findByUserIdAndDeviceId(UUID userId, String deviceId);

    Optional<DeviceToken> findByTokenHash(String tokenHash);

    List<DeviceToken> findAllByUserIdAndEnabledTrueAndRevokedAtIsNull(UUID userId);
}
