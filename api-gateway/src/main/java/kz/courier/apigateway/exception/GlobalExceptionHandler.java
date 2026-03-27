package kz.courier.apigateway.exception;

import kz.courier.apigateway.dto.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handle validation errors
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidationErrors(WebExchangeBindException ex) {
        log.warn("Validation error: {}", ex.getMessage());

        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));

        ApiResponse<Object> response = ApiResponse.error(
                "VALIDATION_ERROR",
                "Invalid input: " + errors
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handle illegal arguments
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());

        ApiResponse<Object> response = ApiResponse.error(
                "INVALID_ARGUMENT",
                ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handle missing or malformed request body (WebFlux)
     */
    @ExceptionHandler(org.springframework.web.server.ServerWebInputException.class)
    public ResponseEntity<ApiResponse<Object>> handleServerWebInputException(org.springframework.web.server.ServerWebInputException ex) {
        log.warn("Malformed request: {}", ex.getMessage());

        ApiResponse<Object> response = ApiResponse.error(
                "BAD_REQUEST",
                "Malformed request body or invalid parameters: " + ex.getReason()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handle explicit ResponseStatusExceptions
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Object>> handleResponseStatusException(ResponseStatusException ex) {
        log.warn("Response status exception: {} - {}", ex.getStatusCode(), ex.getReason());

        ApiResponse<Object> response = ApiResponse.error(
                ex.getStatusCode().toString(),
                ex.getReason() != null ? ex.getReason() : ex.getMessage()
        );

        return ResponseEntity.status(ex.getStatusCode()).body(response);
    }

    /**
     * Handle generic exceptions
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleGenericException(Exception ex) {
        log.error("Unexpected error", ex);

        ApiResponse<Object> response = ApiResponse.error(
                "INTERNAL_ERROR",
                "An unexpected error occurred. Please try again later."
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}