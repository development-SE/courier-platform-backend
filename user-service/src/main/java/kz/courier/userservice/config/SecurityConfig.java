package kz.courier.userservice.config;

import kz.courier.userservice.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity          // enables @PreAuthorize on service/controller methods
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // GET /users and GET /users/{id} — ADMIN and above
                .requestMatchers(HttpMethod.GET,    "/users/**")
                    .hasAnyRole("ADMIN", "SUPER_ADMIN")
                // POST /users — ADMIN and above
                .requestMatchers(HttpMethod.POST,   "/users")
                    .hasAnyRole("ADMIN", "SUPER_ADMIN")
                // PUT /users/{id} — ADMIN and above
                .requestMatchers(HttpMethod.PUT,    "/users/**")
                    .hasAnyRole("ADMIN", "SUPER_ADMIN")
                // DELETE /users/{id} — SUPER_ADMIN only
                .requestMatchers(HttpMethod.DELETE, "/users/**")
                    .hasRole("SUPER_ADMIN")
                // Actuator
                .requestMatchers("/actuator/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
