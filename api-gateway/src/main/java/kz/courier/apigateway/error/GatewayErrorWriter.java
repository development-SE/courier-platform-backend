package kz.courier.apigateway.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.courier.apigateway.observability.CorrelationIdFilter;
import kz.courier.common.error.FieldErrorDetail;
import kz.courier.common.error.StandardErrorResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayErrorWriter {

    private final ObjectMapper objectMapper;

    public StandardErrorResponse body(
            ServerWebExchange exchange,
            HttpStatus status,
            String code,
            String message) {
        return body(exchange, status, code, message, null);
    }

    public StandardErrorResponse body(
            ServerWebExchange exchange,
            HttpStatus status,
            String code,
            String message,
            List<FieldErrorDetail> fieldErrors) {
        return new StandardErrorResponse(
                Instant.now(),
                status.value(),
                status.name(),
                code,
                message,
                exchange.getRequest().getPath().pathWithinApplication().value(),
                traceId(exchange),
                fieldErrors);
    }

    public Mono<Void> write(
            ServerWebExchange exchange,
            HttpStatus status,
            String code,
            String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(CorrelationIdFilter.HEADER, traceId(exchange));

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body(exchange, status, code, message));
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize error response code={} status={}", code, status, ex);
            String fallback = """
                    {"timestamp":"%s","status":%d,"error":"%s","code":"%s","message":"%s","path":"%s","traceId":"%s"}""".formatted(
                    Instant.now(), status.value(), status.name(), code, "Request failed",
                    exchange.getRequest().getPath().pathWithinApplication().value(), traceId(exchange));
            bytes = fallback.getBytes(StandardCharsets.UTF_8);
        }

        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }

    private String traceId(ServerWebExchange exchange) {
        Object traceId = exchange.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        if (traceId instanceof String value && !value.isBlank()) {
            return value;
        }
        String header = exchange.getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER);
        if (header != null && !header.isBlank()) {
            return header;
        }
        return UUID.randomUUID().toString();
    }
}
