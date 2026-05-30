package kz.courier.userservice.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.Key;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final Key secretKey;
    private final long allowedClockSkewSeconds;

    public JwtAuthFilter(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.allowed-clock-skew-seconds:300}") long allowedClockSkewSeconds
    ) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.allowedClockSkewSeconds = allowedClockSkewSeconds;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String gatewayUserId = request.getHeader("X-User-Id");
        String gatewayRoles = request.getHeader("X-User-Roles");
        String gatewayUsername = request.getHeader("X-Username");

        if (gatewayUserId != null && !gatewayUserId.isBlank()
                && gatewayRoles != null && !gatewayRoles.isBlank()) {
            List<SimpleGrantedAuthority> authorities = Arrays.stream(gatewayRoles.split(","))
                    .map(String::trim)
                    .filter(role -> !role.isBlank())
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();

            var auth = new UsernamePasswordAuthenticationToken(gatewayUserId, null, authorities);
            auth.setDetails(gatewayUsername);
            SecurityContextHolder.getContext().setAuthentication(auth);

            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                String token = header.substring(7);
                Claims claims = Jwts.parser()
                        .setSigningKey(secretKey)
                        .clockSkewSeconds(allowedClockSkewSeconds)
                        .build()
                        .parseClaimsJws(token)
                        .getBody();

                String userId = claims.getSubject();
                String role   = claims.get("role", String.class);
                String username = claims.get("username", String.class);

                var auth = new UsernamePasswordAuthenticationToken(
                        userId, null,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role)));
                auth.setDetails(username);
                SecurityContextHolder.getContext().setAuthentication(auth);

            } catch (Exception ignored) {
                // Invalid token — continue as anonymous
            }
        }
        chain.doFilter(request, response);
    }
}
