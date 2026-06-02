package kz.courier.apigateway.exception;

import kz.courier.apigateway.error.GatewayErrorWriter;
import kz.courier.common.error.FieldErrorDetail;
import kz.courier.common.error.StandardErrorResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final GatewayErrorWriter errorWriter;

    /**
     * Handle validation errors
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<StandardErrorResponse> handleValidationErrors(
            WebExchangeBindException ex,
            ServerWebExchange exchange) {
        List<FieldErrorDetail> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorDetail(error.getField(), error.getDefaultMessage()))
                .toList();
        log.warn("Validation error path={} fields={}",
                exchange.getRequest().getPath().pathWithinApplication().value(), fieldErrors.size());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errorWriter.body(
                        exchange,
                        HttpStatus.BAD_REQUEST,
                        "VALIDATION_ERROR",
                        "Request validation failed",
                        fieldErrors));
    }

    /**
     * Handle illegal arguments
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<StandardErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex,
            ServerWebExchange exchange) {
        log.warn("Illegal argument: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errorWriter.body(exchange, HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", ex.getMessage()));
    }

    /**
     * Handle missing or malformed request body (WebFlux)
     */
    @ExceptionHandler(org.springframework.web.server.ServerWebInputException.class)
    public ResponseEntity<StandardErrorResponse> handleServerWebInputException(
            org.springframework.web.server.ServerWebInputException ex,
            ServerWebExchange exchange) {
        log.warn("Malformed request: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errorWriter.body(
                        exchange,
                        HttpStatus.BAD_REQUEST,
                        "BAD_REQUEST",
                        "Malformed request body or invalid parameters"));
    }

    /**
     * Handle explicit ResponseStatusExceptions
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<StandardErrorResponse> handleResponseStatusException(
            ResponseStatusException ex,
            ServerWebExchange exchange) {
        log.warn("Response status exception: {} - {}", ex.getStatusCode(), ex.getReason());
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        return ResponseEntity.status(status)
                .body(errorWriter.body(
                        exchange,
                        status,
                        codeForStatus(status, ex.getReason()),
                        safeReason(status, ex.getReason())));
    }

    /**
     * Handle generic exceptions
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<StandardErrorResponse> handleGenericException(Exception ex, ServerWebExchange exchange) {
        log.error("Unexpected error", ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorWriter.body(
                        exchange,
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "INTERNAL_ERROR",
                        "An unexpected error occurred. Please try again later."));
    }

    private String codeForStatus(HttpStatus status, String reason) {
        if (reason != null && reason.matches("[A-Z0-9_]+")) {
            return reason;
        }
        return switch (status) {
            case UNAUTHORIZED -> "UNAUTHORIZED";
            case FORBIDDEN -> "FORBIDDEN";
            case NOT_FOUND -> "NOT_FOUND";
            case CONFLICT -> "CONFLICT";
            case SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE";
            default -> status.name();
        };
    }

    private String safeReason(HttpStatus status, String reason) {
        if (reason == null || reason.isBlank() || reason.matches("[A-Z0-9_]+")) {
            return switch (status) {
                case UNAUTHORIZED -> "Authentication is required";
                case FORBIDDEN -> "Access denied";
                case NOT_FOUND -> "Resource not found";
                case SERVICE_UNAVAILABLE -> "Service is temporarily unavailable";
                default -> "Request failed";
            };
        }
        return reason;
    }
}
