package kz.courier.apigateway.controller;

import kz.courier.apigateway.dto.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @GetMapping("/auth")
    public ResponseEntity<ApiResponse<Object>> authServiceFallback() {
        log.error("Circuit breaker: Auth service is unavailable");

        ApiResponse<Object> response = ApiResponse.error(
                "SERVICE_UNAVAILABLE",
                "Authentication service is temporarily unavailable. Please try again in a few moments."
        );

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    @GetMapping("/order")
    public ResponseEntity<ApiResponse<Object>> orderServiceFallback() {
        log.error("Circuit breaker: Order service is unavailable");

        ApiResponse<Object> response = ApiResponse.error(
                "SERVICE_UNAVAILABLE",
                "Order service is temporarily unavailable. Please try again in a few moments."
        );

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    @GetMapping("/courier")
    public ResponseEntity<ApiResponse<Object>> courierServiceFallback() {
        log.error("Circuit breaker: Courier service is unavailable");

        ApiResponse<Object> response = ApiResponse.error(
                "SERVICE_UNAVAILABLE",
                "Courier service is temporarily unavailable. Please try again in a few moments."
        );

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }
}