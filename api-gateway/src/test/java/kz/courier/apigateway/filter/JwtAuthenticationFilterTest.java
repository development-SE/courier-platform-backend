package kz.courier.apigateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import kz.courier.apigateway.error.GatewayErrorWriter;
import kz.courier.apigateway.observability.CorrelationIdFilter;
import kz.courier.apigateway.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthenticationFilterTest {

    private static final String SECRET = "0123456789012345678901234567890123456789012345678901234567890123";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        ReflectionTestUtils.setField(jwtUtil, "allowedClockSkewSeconds", 0L);
        filter = new JwtAuthenticationFilter(jwtUtil, new GatewayErrorWriter(objectMapper));
    }

    @Test
    void should_ReturnUnauthorized_When_ProtectedEndpointHasNoToken() throws Exception {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/v1/orders")
                .header(CorrelationIdFilter.HEADER, "trace-123")
                .build());

        filter.apply(new JwtAuthenticationFilter.Config())
                .filter(exchange, rejectedChain("JWT filter should stop unauthenticated request"))
                .block();

        JsonNode json = responseBody(exchange);
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
        assertThat(json.path("status").asInt()).isEqualTo(401);
        assertThat(json.path("code").asText()).isEqualTo("AUTH_HEADER_MISSING");
        assertThat(json.path("traceId").asText()).isEqualTo("trace-123");
    }

    @Test
    void should_AllowProtectedEndpoint_When_TokenIsValidAndRoleAllowed() {
        String token = token("user-123", "client@example.com", "CLIENT", "company-1", Instant.now().plusSeconds(600));
        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        AtomicReference<ServerWebExchange> mutatedExchange = new AtomicReference<>();

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build());

        filter.apply(new JwtAuthenticationFilter.Config())
                .filter(exchange, ex -> {
                    chainInvoked.set(true);
                    mutatedExchange.set(ex);
                    return Mono.empty();
                })
                .block();

        assertThat(chainInvoked.get()).isTrue();
        assertThat(mutatedExchange.get().getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo("user-123");
        assertThat(mutatedExchange.get().getRequest().getHeaders().getFirst("X-Username")).isEqualTo("client@example.com");
        assertThat(mutatedExchange.get().getRequest().getHeaders().getFirst("X-User-Roles")).isEqualTo("CLIENT");
        assertThat(mutatedExchange.get().getRequest().getHeaders().getFirst("X-Company-Id")).isEqualTo("company-1");
    }

    @Test
    void should_StripSpoofedIdentityHeaders_And_AddTrustedJwtValues() {
        String token = token("user-123", "client@example.com", "CLIENT", "company-1", Instant.now().plusSeconds(600));
        AtomicReference<ServerWebExchange> mutatedExchange = new AtomicReference<>();

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-User-Id", "attacker-id")
                .header("X-Username", "attacker")
                .header("X-User-Roles", "SUPER_ADMIN")
                .header("X-Company-Id", "fake-company")
                .build());

        filter.apply(new JwtAuthenticationFilter.Config())
                .filter(exchange, ex -> {
                    mutatedExchange.set(ex);
                    return Mono.empty();
                })
                .block();

        HttpHeaders headers = mutatedExchange.get().getRequest().getHeaders();
        assertThat(headers.get("X-User-Id")).containsExactly("user-123");
        assertThat(headers.get("X-Username")).containsExactly("client@example.com");
        assertThat(headers.get("X-User-Roles")).containsExactly("CLIENT");
        assertThat(headers.get("X-Company-Id")).containsExactly("company-1");
    }

    @Test
    void should_ReturnUnauthorized_When_TokenIsExpired() throws Exception {
        String token = token("user-123", "client@example.com", "CLIENT", null, Instant.now().minusSeconds(600));
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build());

        filter.apply(new JwtAuthenticationFilter.Config())
                .filter(exchange, rejectedChain("Expired token should not reach downstream"))
                .block();

        JsonNode json = responseBody(exchange);
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
        assertThat(json.path("code").asText()).isEqualTo("JWT_EXPIRED");
        assertThat(json.path("message").asText()).doesNotContain(token);
    }

    @Test
    void should_ReturnUnauthorized_When_TokenSignatureInvalid() throws Exception {
        String token = tokenWithSecret(
                "different-secret-different-secret-different-secret-different-secret",
                "user-123",
                Map.of("username", "client@example.com", "role", "CLIENT"),
                Instant.now().plusSeconds(600)
        );
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build());

        filter.apply(new JwtAuthenticationFilter.Config())
                .filter(exchange, rejectedChain("Invalid signature should not reach downstream"))
                .block();

        JsonNode json = responseBody(exchange);
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
        assertThat(json.path("code").asText()).isEqualTo("JWT_SIGNATURE_INVALID");
        assertThat(json.path("message").asText()).doesNotContain(token);
    }

    private MockServerWebExchange exchange(MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        String traceId = request.getHeaders().getFirst(CorrelationIdFilter.HEADER);
        if (traceId != null) {
            exchange.getAttributes().put(CorrelationIdFilter.ATTRIBUTE, traceId);
        }
        return exchange;
    }

    private GatewayFilterChain rejectedChain(String message) {
        return ignored -> {
            throw new AssertionError(message);
        };
    }

    private JsonNode responseBody(MockServerWebExchange exchange) throws Exception {
        String response = exchange.getResponse().getBodyAsString().block();
        return objectMapper.readTree(response);
    }

    private String token(
            String userId,
            String username,
            String role,
            String companyId,
            Instant expiresAt) {
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("username", username);
        claims.put("role", role);
        if (companyId != null) {
            claims.put("companyId", companyId);
        }
        return tokenWithSecret(SECRET, userId, claims, expiresAt);
    }

    private String tokenWithSecret(
            String secret,
            String userId,
            Map<String, Object> claims,
            Instant expiresAt) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(userId)
                .claims(claims)
                .issuedAt(Date.from(Instant.now().minusSeconds(60)))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }
}
