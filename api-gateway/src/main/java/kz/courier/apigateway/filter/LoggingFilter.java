package kz.courier.apigateway.filter;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class LoggingFilter extends AbstractGatewayFilterFactory<LoggingFilter.Config> {

    public LoggingFilter() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            long startTime = System.currentTimeMillis();

            String requestPath = exchange.getRequest().getURI().getPath();
            String requestMethod = exchange.getRequest().getMethod().name();
            String requestId = exchange.getRequest().getId();

            log.info("→ Request: {} {} [ID: {}]", requestMethod, requestPath, requestId);

            return chain.filter(exchange)
                    .then(Mono.fromRunnable(() -> {
                        long duration = System.currentTimeMillis() - startTime;
                        int statusCode = exchange.getResponse().getStatusCode() != null
                                ? exchange.getResponse().getStatusCode().value()
                                : 0;

                        log.info("← Response: {} {} - Status: {} - Duration: {}ms",
                                requestMethod, requestPath, statusCode, duration);
                    }));
        };
    }

    @Data
    public static class Config {
        // Configuration properties if needed
    }
}