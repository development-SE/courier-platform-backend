package kz.courier.apigateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.courier.apigateway.dto.response.ApiResponse;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class RoleFilter extends AbstractGatewayFilterFactory<RoleFilter.Config> {

    private final ObjectMapper objectMapper;

    public RoleFilter(ObjectMapper objectMapper) {
        super(Config.class);
        this.objectMapper = objectMapper;
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

            // Check if user has any of the required roles
            boolean hasRequiredRole = config.getRoles().stream()
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
            log.error("Failed to serialize role filter error response", e);
            return "{\"success\":false,\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}";
        }
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
