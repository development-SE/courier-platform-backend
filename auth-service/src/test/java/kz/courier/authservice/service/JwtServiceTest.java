package kz.courier.authservice.service;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class JwtServiceTest {

    private static final String SECRET = "0123456789012345678901234567890123456789012345678901234567890123";

    @Test
    void should_GenerateRefreshTokenClaims_When_RefreshTokenCreated() {
        JwtService jwtService = new JwtService(SECRET, 15, 60);
        UUID userId = UUID.randomUUID();

        JwtService.RefreshTokenDetails refreshToken = jwtService.generateRefreshToken(userId);
        Claims claims = jwtService.validateAndGetClaims(refreshToken.token());

        assertThat(refreshToken.tokenId()).isNotNull();
        assertThat(claims.getId()).isEqualTo(refreshToken.tokenId().toString());
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("token_type", String.class)).isEqualTo("refresh");
        assertThat(claims.getIssuedAt().toInstant()).isCloseTo(refreshToken.issuedAt(), within(1, ChronoUnit.SECONDS));
        assertThat(claims.getExpiration().toInstant()).isCloseTo(refreshToken.expiresAt(), within(1, ChronoUnit.SECONDS));
    }

    @Test
    void should_NotMarkAccessTokenAsRefresh_When_AccessTokenCreated() {
        JwtService jwtService = new JwtService(SECRET, 15, 60);

        String accessToken = jwtService.generateAccessToken(
                UUID.randomUUID(),
                "client@example.com",
                "CLIENT",
                UUID.randomUUID()
        );
        Claims claims = jwtService.validateAndGetClaims(accessToken);

        assertThat(claims.get("token_type", String.class)).isNull();
        assertThat(claims.get("username", String.class)).isEqualTo("client@example.com");
        assertThat(claims.get("role", String.class)).isEqualTo("CLIENT");
        assertThat(claims.getSubject()).isNotBlank();
    }
}
