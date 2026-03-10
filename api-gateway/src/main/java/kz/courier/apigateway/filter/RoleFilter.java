package kz.courier.apigateway.filter;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class RoleFilter extends AbstractGatewayFilterFactory<RoleFilter.Config> {

    public RoleFilter() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            // Extract roles from header (set by JwtAuthenticationFilter)
            List<String> rolesHeader = exchange.getRequest().getHeaders().get("X-User-Roles");

            if (rolesHeader == null || rolesHeader.isEmpty()) {
                log.warn("No roles found in request headers");
                return onError(exchange, "Access denied: No roles found", HttpStatus.FORBIDDEN);
            }

            String rolesString = rolesHeader.get(0);
            List<String> userRoles = Arrays.asList(rolesString.split(","));

            // Check if user has any of the required roles
            boolean hasRequiredRole = config.getRoles().stream()
                    .anyMatch(userRoles::contains);

            if (!hasRequiredRole) {
                log.warn("User roles {} do not match required roles {}",
                        userRoles, config.getRoles());
                return onError(exchange, "Access denied: Insufficient permissions",
                        HttpStatus.FORBIDDEN);
            }

            log.debug("Role validation passed for roles: {}", userRoles);
            return chain.filter(exchange);
        };
    }

    private Mono<Void> onError(ServerWebExchange exchange, String message, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json");

        String errorResponse = String.format("{\"error\": \"%s\", \"status\": %d}",
                message, status.value());

        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse()
                        .bufferFactory()
                        .wrap(errorResponse.getBytes())));
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return Arrays.asList("roles");
    }

    @Data
    @NoArgsConstructor
    public static class Config {
        private List<String> roles;
    }
}