package kz.courier.logisticsservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Security configuration for logistics-service.
 *
 * <p>JWT validation is handled exclusively by the API Gateway.
 * This service simply trusts the {@code X-User-Id} and {@code X-User-Roles}
 * headers injected by the Gateway after successful token verification.
 *
 * <p>Role model enforced here:
 * <ul>
 *   <li>ADMIN / SUPER_ADMIN — full access to all endpoints</li>
 *   <li>COURIER — can update own location and read own assignments</li>
 *   <li>MANAGER / DIRECTOR — read assignments, manage statuses</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new GatewayHeaderAuthFilter(),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // Actuator
                        .requestMatchers("/actuator/**").permitAll()

                        // Nearby-courier search — admins and internal callers only
                        .requestMatchers(HttpMethod.GET, "/couriers/nearby")
                        .hasAnyRole("ADMIN", "SUPER_ADMIN", "MANAGER", "DIRECTOR")

                        // Couriers update their own location / online status
                        .requestMatchers(HttpMethod.PUT, "/couriers/me/location").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/couriers/me/online").authenticated()
                        .requestMatchers(HttpMethod.GET, "/couriers/*/location").authenticated()

                        // Assignments — read for couriers, write for admins/managers
                        .requestMatchers(HttpMethod.POST, "/assignments/auto/**")
                        .hasAnyRole("ADMIN", "SUPER_ADMIN", "MANAGER", "DIRECTOR")
                        .requestMatchers(HttpMethod.POST, "/assignments/manual")
                        .hasAnyRole("ADMIN", "SUPER_ADMIN", "MANAGER", "DIRECTOR")
                        .requestMatchers(HttpMethod.POST, "/assignments")
                        .hasAnyRole("ADMIN", "SUPER_ADMIN", "MANAGER", "DIRECTOR")
                        .requestMatchers(HttpMethod.GET, "/assignments/manual-required")
                        .hasAnyRole("ADMIN", "SUPER_ADMIN", "MANAGER", "DIRECTOR")
                        .requestMatchers(HttpMethod.PATCH, "/assignments/*/status").authenticated()
                        .requestMatchers(HttpMethod.GET, "/assignments/**").authenticated()

                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex.accessDeniedHandler(accessDeniedHandler()));

        return http.build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Filter: build Spring Security principal from Gateway-injected headers
    // ─────────────────────────────────────────────────────────────────────────

    @Slf4j
    static class GatewayHeaderAuthFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain)
                throws ServletException, IOException {

            String userId = request.getHeader("X-User-Id");
            String rolesHeader = request.getHeader("X-User-Roles");

            if (userId != null && rolesHeader != null) {
                List<SimpleGrantedAuthority> authorities = Arrays
                        .stream(rolesHeader.split(","))
                        .map(String::trim)
                        .filter(r -> !r.isBlank())
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .toList();

                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userId, null, authorities);

                SecurityContextHolder.getContext().setAuthentication(auth);
                log.debug("Authenticated userId={} roles={}", userId, rolesHeader);
            }

            chain.doFilter(request, response);
        }
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");

            var body = new java.util.HashMap<String, Object>();
            body.put("error", "FORBIDDEN");
            body.put("message", "You do not have permission to access this resource");
            body.put("path", request.getRequestURI());

            new ObjectMapper().writeValue(response.getOutputStream(), body);
        };
    }
}
