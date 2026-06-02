package kz.courier.apigateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.courier.apigateway.error.GatewayErrorWriter;
import kz.courier.apigateway.observability.CorrelationIdFilter;
import kz.courier.apigateway.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JwtAuthenticationFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void missingAuthorizationHeaderReturnsStandardErrorWithTraceId() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                mock(JwtUtil.class),
                new GatewayErrorWriter(objectMapper));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/orders")
                        .header(CorrelationIdFilter.HEADER, "trace-123")
                        .build());
        exchange.getAttributes().put(CorrelationIdFilter.ATTRIBUTE, "trace-123");

        filter.apply(new JwtAuthenticationFilter.Config())
                .filter(exchange, ignored -> {
                    throw new AssertionError("JWT filter should stop unauthenticated request");
                })
                .block();

        String response = exchange.getResponse().getBodyAsString().block();
        JsonNode json = objectMapper.readTree(response);

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.HEADER)).isEqualTo("trace-123");
        assertThat(json.path("status").asInt()).isEqualTo(401);
        assertThat(json.path("error").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(json.path("code").asText()).isEqualTo("AUTH_HEADER_MISSING");
        assertThat(json.path("path").asText()).isEqualTo("/api/v1/orders");
        assertThat(json.path("traceId").asText()).isEqualTo("trace-123");
    }
}
