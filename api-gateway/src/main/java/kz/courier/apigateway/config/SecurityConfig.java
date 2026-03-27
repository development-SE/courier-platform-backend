package kz.courier.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(ServerHttpSecurity.CorsSpec::disable)  // Using CorsWebFilter instead
                .authorizeExchange(exchange -> exchange
                        // Public endpoints
                        .pathMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/verify",
                                "/api/v1/health",
                                "/api/v1/ping",
                                "/actuator/**",
                                "/fallback/**"
                        ).permitAll()
                        // All other endpoints require authentication
                        .anyExchange().permitAll()  // Temporarily permit all for testing
                )
                .build();
    }
}