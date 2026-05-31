package kz.courier.courierservice.config;

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

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new GatewayHeaderAuthFilter(), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/couriers/me").authenticated()
                .requestMatchers(HttpMethod.GET, "/couriers/*/eligibility").authenticated()
                .requestMatchers(HttpMethod.GET, "/couriers", "/couriers/**")
                    .hasAnyRole("ADMIN", "SUPER_ADMIN", "MANAGER", "DIRECTOR")
                .requestMatchers(HttpMethod.POST, "/couriers")
                    .hasAnyRole("ADMIN", "SUPER_ADMIN", "MANAGER", "DIRECTOR", "COURIER")
                .requestMatchers(HttpMethod.PUT, "/couriers/**")
                    .hasAnyRole("ADMIN", "SUPER_ADMIN", "MANAGER", "DIRECTOR", "COURIER")
                .anyRequest().authenticated()
            )
                .exceptionHandling(ex -> ex.accessDeniedHandler(accessDeniedHandler()));

        return http.build();
    }

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
                List<SimpleGrantedAuthority> authorities = Arrays.stream(rolesHeader.split(","))
                        .map(String::trim)
                        .filter(role -> !role.isBlank())
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
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
