package kz.courier.apigateway.controller;

import kz.courier.apigateway.config.SecurityConfig;
import kz.courier.apigateway.dto.request.LoginRequest;
import kz.courier.apigateway.dto.response.ApiResponse;
import kz.courier.apigateway.dto.response.LoginResponse;
import kz.courier.apigateway.error.GatewayErrorWriter;
import kz.courier.apigateway.exception.GlobalExceptionHandler;
import kz.courier.apigateway.grpc.AuthClient;
import kz.courier.apigateway.observability.CorrelationIdFilter;
import kz.courier.apigateway.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.beans.factory.annotation.Autowired;

@WebFluxTest(controllers = AuthController.class)
@Import({
        SecurityConfig.class,
        GlobalExceptionHandler.class,
        GatewayErrorWriter.class,
        CorrelationIdFilter.class,
        AuthControllerTest.StubConfig.class
})
class AuthControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void should_AllowPublicAuthEndpoint_WithoutToken() {
        webTestClient.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new LoginRequest("client@example.com", "StrongPass1!", "device-1"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.accessToken").isEqualTo("access-token")
                .jsonPath("$.data.refreshToken").isEqualTo("refresh-token");
    }

    @Test
    void should_ReturnBadRequest_When_RequiredFieldsMissing() {
        webTestClient.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.timestamp").exists()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.error").isEqualTo("BAD_REQUEST")
                .jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.message").isEqualTo("Request validation failed")
                .jsonPath("$.path").isEqualTo("/api/v1/auth/login")
                .jsonPath("$.traceId").exists()
                .jsonPath("$.fieldErrors.length()").isEqualTo(2);
    }

    @TestConfiguration
    static class StubConfig {

        @Bean
        AuthClient authClient() {
            return new AuthClient() {
                @Override
                public ApiResponse<LoginResponse> login(LoginRequest request) {
                    return ApiResponse.success(LoginResponse.builder()
                            .accessToken("access-token")
                            .refreshToken("refresh-token")
                            .expiresAt(1_700_000_000L)
                            .role("CLIENT")
                            .build());
                }
            };
        }

        @Bean
        JwtUtil jwtUtil() {
            JwtUtil jwtUtil = new JwtUtil();
            ReflectionTestUtils.setField(jwtUtil, "secret",
                    "0123456789012345678901234567890123456789012345678901234567890123");
            ReflectionTestUtils.setField(jwtUtil, "allowedClockSkewSeconds", 0L);
            return jwtUtil;
        }
    }
}
