package kz.courier.authservice.repository;

import kz.courier.authservice.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    List<RefreshToken> findByTokenFamilyId(UUID tokenFamilyId);
    List<RefreshToken> findByUserIdAndRevokedAtIsNull(UUID userId);
}
