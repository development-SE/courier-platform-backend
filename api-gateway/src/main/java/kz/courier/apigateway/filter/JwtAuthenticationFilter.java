package kz.courier.apigateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SecurityException;
import kz.courier.apigateway.dto.response.ApiResponse;
import kz.courier.apigateway.security.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class JwtAuthenticationFilter extends AbstractGatewayFilterFactory<JwtAuthenticationFilter.Config> {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, ObjectMapper objectMapper) {
        super(Config.class);
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();

            // Extract Authorization header
            if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                log.warn("Missing Authorization header");
                return onError(exchange, "AUTH_HEADER_MISSING", "Missing Authorization header", HttpStatus.UNAUTHORIZED);
            }

            String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("Invalid Authorization header format");
                return onError(exchange, "AUTH_HEADER_INVALID", "Invalid Authorization header format", HttpStatus.UNAUTHORIZED);
            }

            String token = authHeader.substring(7);

            try {
                Claims claims = jwtUtil.extractAllClaims(token);
                String userId = claims.getSubject();
                String username = claims.get("username", String.class);
                String role = claims.get("role", String.class);
                String companyId = claims.get("companyId", String.class);
                String forwardedUsername = username != null ? username : userId;

                // Add user info to headers for downstream services
                ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                        .header("X-User-Id", userId)
                        .header("X-Username", forwardedUsername)
                        .header("X-User-Roles", role)
                        .header("X-Company-Id", companyId != null ? companyId : "") 
                        .build();

                log.debug("JWT validated successfully for user: {}", forwardedUsername);

                return chain.filter(exchange.mutate().request(modifiedRequest).build());

            } catch (ExpiredJwtException e) {
                log.warn("JWT expired for request {}: {}", request.getURI(), e.getMessage());
                return onError(exchange, "JWT_EXPIRED", "Access token has expired", HttpStatus.UNAUTHORIZED);
            } catch (SecurityException e) {
                log.warn("JWT signature validation failed for request {}: {}", request.getURI(), e.getMessage());
                return onError(exchange, "JWT_SIGNATURE_INVALID", "JWT signature is invalid", HttpStatus.UNAUTHORIZED);
            } catch (MalformedJwtException e) {
                log.warn("Malformed JWT for request {}: {}", request.getURI(), e.getMessage());
                return onError(exchange, "JWT_MALFORMED", "JWT token is malformed", HttpStatus.UNAUTHORIZED);
            } catch (UnsupportedJwtException e) {
                log.warn("Unsupported JWT for request {}: {}", request.getURI(), e.getMessage());
                return onError(exchange, "JWT_UNSUPPORTED", "JWT token type is unsupported", HttpStatus.UNAUTHORIZED);
            } catch (IllegalArgumentException e) {
                log.warn("Empty JWT for request {}: {}", request.getURI(), e.getMessage());
                return onError(exchange, "JWT_MISSING", "JWT token is missing or empty", HttpStatus.UNAUTHORIZED);
            } catch (Exception e) {
                log.error("JWT validation error: {}", e.getMessage());
                return onError(exchange, "JWT_INVALID", "JWT validation failed", HttpStatus.UNAUTHORIZED);
            }
        };
    }

    private Mono<Void> onError(ServerWebExchange exchange, String code, String message, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String errorResponse = toJson(code, message);

        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse()
                        .bufferFactory()
                        .wrap(errorResponse.getBytes(StandardCharsets.UTF_8))));
    }

    private String toJson(String code, String message) {
        try {
            return objectMapper.writeValueAsString(ApiResponse.error(code, message));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize JWT error response", e);
            return "{\"success\":false,\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}";
        }
    }

    public static class Config {
        // Configuration properties if needed
    }
}
