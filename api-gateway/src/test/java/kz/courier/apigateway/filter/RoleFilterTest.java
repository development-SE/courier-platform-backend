package kz.courier.apigateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.courier.apigateway.error.GatewayErrorWriter;
import kz.courier.apigateway.observability.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class RoleFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final RoleFilter filter = new RoleFilter(new GatewayErrorWriter(objectMapper));

    @Test
    void should_ReturnForbidden_When_UserRoleIsNotAllowed() throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/admin/manual-assignment")
                        .header("X-User-Roles", "CLIENT")
                        .header(CorrelationIdFilter.HEADER, "trace-403")
                        .build());
        exchange.getAttributes().put(CorrelationIdFilter.ATTRIBUTE, "trace-403");

        filter.apply(new RoleFilter.Config("ADMIN", "SUPER_ADMIN"))
                .filter(exchange, ignored -> {
                    throw new AssertionError("Role filter should block disallowed role");
                })
                .block();

        JsonNode json = objectMapper.readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(403);
        assertThat(json.path("status").asInt()).isEqualTo(403);
        assertThat(json.path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(json.path("traceId").asText()).isEqualTo("trace-403");
    }

    @Test
    void should_AllowProtectedEndpoint_When_UserRoleIsAllowed() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/admin/manual-assignment")
                        .header("X-User-Roles", "CLIENT,ADMIN")
                        .build());

        filter.apply(new RoleFilter.Config("ADMIN"))
                .filter(exchange, ignored -> Mono.empty())
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}
