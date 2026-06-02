package kz.courier.authservice.service;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.security.Key;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtService {

    private final Key secretKey;
    private final long accessExpMs;
    private final long refreshExpMs;

    public JwtService(@Value("${jwt.secret}") String secret,
                      @Value("${jwt.access-expiry-min}") long accessMin,
                      @Value("${jwt.refresh-expiry-min}") long refreshMin) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes());
        this.accessExpMs = accessMin * 60 * 1000;
        this.refreshExpMs = refreshMin * 60 * 1000;
    }

    public String generateAccessToken(UUID userId, String username, String role, UUID companyId) {
        var builder = Jwts.builder()
                .setSubject(userId.toString())
                .claim("username", username)
                .claim("role", role)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + accessExpMs))
                .signWith(secretKey);
        if (companyId != null) {
            builder.claim("companyId", companyId.toString());
        }

        return builder.compact();
    }

    public RefreshTokenDetails generateRefreshToken(UUID userId) {
        UUID tokenId = UUID.randomUUID();
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusMillis(refreshExpMs);

        String token = Jwts.builder()
                .setId(tokenId.toString())
                .setSubject(userId.toString())
                .claim("token_type", "refresh")
                .setIssuedAt(Date.from(issuedAt))
                .setExpiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();

        return new RefreshTokenDetails(token, tokenId, issuedAt, expiresAt);
    }

    public Claims validateAndGetClaims(String token) {
        return Jwts.parser()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public record RefreshTokenDetails(
            String token,
            UUID tokenId,
            Instant issuedAt,
            Instant expiresAt
    ) {
    }
}
