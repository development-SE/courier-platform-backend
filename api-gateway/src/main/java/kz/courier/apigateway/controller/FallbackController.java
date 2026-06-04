package kz.courier.apigateway.controller;

import kz.courier.apigateway.error.GatewayErrorWriter;
import kz.courier.common.error.StandardErrorResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

@Slf4j
@RestController
@RequestMapping("/fallback")
@RequiredArgsConstructor
public class FallbackController {

    private final GatewayErrorWriter errorWriter;

    @GetMapping("/auth")
    public ResponseEntity<StandardErrorResponse> authServiceFallback(ServerWebExchange exchange) {
        log.error("Circuit breaker: Auth service is unavailable");

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(errorWriter.body(
                        exchange,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "SERVICE_UNAVAILABLE",
                        "Authentication service is temporarily unavailable. Please try again in a few moments."));
    }

    @GetMapping("/order")
    public ResponseEntity<StandardErrorResponse> orderServiceFallback(ServerWebExchange exchange) {
        log.error("Circuit breaker: Order service is unavailable");

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(errorWriter.body(
                        exchange,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "SERVICE_UNAVAILABLE",
                        "Order service is temporarily unavailable. Please try again in a few moments."));
    }

    @GetMapping("/courier")
    public ResponseEntity<StandardErrorResponse> courierServiceFallback(ServerWebExchange exchange) {
        log.error("Circuit breaker: Courier service is unavailable");

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(errorWriter.body(
                        exchange,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "SERVICE_UNAVAILABLE",
                        "Courier service is temporarily unavailable. Please try again in a few moments."));
    }
}
