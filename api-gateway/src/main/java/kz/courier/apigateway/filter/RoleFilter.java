package kz.courier.apigateway.filter;

import kz.courier.apigateway.error.GatewayErrorWriter;
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

    private final GatewayErrorWriter errorWriter;

    public RoleFilter(GatewayErrorWriter errorWriter) {
        super(Config.class);
        this.errorWriter = errorWriter;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            // Extract roles from header (set by JwtAuthenticationFilter)
            List<String> rolesHeader = exchange.getRequest().getHeaders().get("X-User-Roles");

            if (rolesHeader == null || rolesHeader.isEmpty()) {
                log.warn("No roles found in request headers");
                return onError(exchange, "ROLES_MISSING", "Access denied: No roles found", HttpStatus.FORBIDDEN);
            }

            String rolesString = rolesHeader.get(0);
            List<String> userRoles = Arrays.asList(rolesString.split(","));

            // Check if user has any of the required roles or is SUPER_ADMIN
            boolean hasRequiredRole = userRoles.contains("SUPER_ADMIN") || config.getRoles().stream()
                    .anyMatch(userRoles::contains);

            if (!hasRequiredRole) {
                log.warn("User roles {} do not match required roles {}",
                        userRoles, config.getRoles());
                return onError(exchange, "FORBIDDEN", "Access denied: Insufficient permissions",
                        HttpStatus.FORBIDDEN);
            }

            log.debug("Role validation passed for roles: {}", userRoles);
            return chain.filter(exchange);
        };
    }

    private Mono<Void> onError(ServerWebExchange exchange, String code, String message, HttpStatus status) {
        return errorWriter.write(exchange, status, code, message);
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return Arrays.asList("roles");
    }

    @Data
    @NoArgsConstructor
    public static class Config {
        private List<String> roles;

        public Config(String... roles) {
            this.roles = Arrays.asList(roles);
        }
    }
}
