package kz.courier.apigateway.filter;

import io.jsonwebtoken.Claims;
import kz.courier.apigateway.security.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Spring Cloud Gateway filter that authenticates every inbound HTTP request.
 *
 * <h3>Security contract</h3>
 * <ol>
 *   <li><b>Strip spoofed identity headers</b> — remove any client-supplied
 *       {@code X-User-Id}, {@code X-Username} and {@code X-User-Roles} before
 *       adding our own.  This closes the header-injection attack surface.</li>
 *   <li><b>Validate JWT</b> — reject the request with 401 if the token is
 *       missing, malformed, or expired.</li>
 *   <li><b>Propagate trusted identity headers</b> — downstream HTTP services
 *       receive {@code X-User-Id}, {@code X-Username}, {@code X-User-Roles} set
 *       exclusively by this filter.</li>
 * </ol>
 *
 * <h3>gRPC propagation</h3>
 * The {@link kz.courier.apigateway.grpc.GatewayGrpcContext} ThreadLocal is
 * populated by {@link kz.courier.apigateway.controller.OrderController#withAuth}
 * so that {@link kz.courier.apigateway.grpc.AuthForwardingInterceptor} can attach
 * the same identity + raw JWT to every outbound gRPC metadata frame.
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends AbstractGatewayFilterFactory<JwtAuthenticationFilter.Config> {

    /** Header names whose values MUST be set only by this trusted gateway. */
    private static final String HDR_USER_ID    = "X-User-Id";
    private static final String HDR_USERNAME   = "X-Username";
    private static final String HDR_USER_ROLES = "X-User-Roles";

    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        super(Config.class);
        this.jwtUtil = jwtUtil;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();

            // ── 1. Require Authorization header ───────────────────────────────
            if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                log.warn("[JwtFilter] Missing Authorization header");
                return onError(exchange, "Missing Authorization header", HttpStatus.UNAUTHORIZED);
            }

            String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("[JwtFilter] Invalid Authorization header format");
                return onError(exchange, "Invalid Authorization header format", HttpStatus.UNAUTHORIZED);
            }

            String token = authHeader.substring(7);

            try {
                // ── 2. Validate JWT ───────────────────────────────────────────
                if (!jwtUtil.validateToken(token)) {
                    log.warn("[JwtFilter] Invalid JWT token");
                    return onError(exchange, "Invalid JWT token", HttpStatus.UNAUTHORIZED);
                }

                Claims claims  = jwtUtil.extractAllClaims(token);
                String userId  = claims.getSubject();
                String username = claims.get("username", String.class);
                String roles   = claims.get("role", String.class);
                if (roles == null) {
                    roles = claims.get("roles", String.class);
                }

                // ── 3. Strip spoofed headers, then add gateway-trusted values ─
                //       Any client-supplied X-User-* is removed first so that
                //       downstream services cannot be tricked by a crafted request.
                ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                        .headers(h -> {
                            h.remove(HDR_USER_ID);
                            h.remove(HDR_USERNAME);
                            h.remove(HDR_USER_ROLES);
                        })
                        .header(HDR_USER_ID,    userId)
                        .header(HDR_USERNAME,   username)
                        .header(HDR_USER_ROLES, roles)
                        .build();

                log.debug("[JwtFilter] Token valid — userId={} username={} roles={}", userId, username, roles);

                return chain.filter(exchange.mutate().request(modifiedRequest).build());

            } catch (Exception e) {
                log.error("[JwtFilter] JWT validation error: {}", e.getMessage());
                return onError(exchange, "JWT validation failed", HttpStatus.UNAUTHORIZED);
            }
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Mono<Void> onError(ServerWebExchange exchange, String message, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().set("Content-Type", "application/json");

        String body = String.format("{\"error\":\"%s\",\"status\":%d}", message, status.value());

        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse()
                        .bufferFactory()
                        .wrap(body.getBytes())));
    }

    public static class Config {
        // Future: per-route allow-list / bypass rules
    }
}