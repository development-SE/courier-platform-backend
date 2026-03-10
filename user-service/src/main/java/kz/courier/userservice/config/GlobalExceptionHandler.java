package kz.courier.userservice.config;

import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Validation errors (@Valid) */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage,
                        (a, b) -> a));
        return ResponseEntity.badRequest().body(Map.of(
                "status",    400,
                "error",     "VALIDATION_ERROR",
                "details",   errors,
                "timestamp", Instant.now().toString()
        ));
    }

    /** ResponseStatusException (thrown by service layer) */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "status",    ex.getStatusCode().value(),
                "error",     ex.getReason() != null ? ex.getReason() : ex.getMessage(),
                "timestamp", Instant.now().toString()
        ));
    }

    /** Access denied (wrong role) */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "status",    403,
                "error",     "ACCESS_DENIED: You don't have permission to perform this action",
                "timestamp", Instant.now().toString()
        ));
    }

    /** Catch-all */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "status",    500,
                "error",     "INTERNAL_ERROR",
                "message",   ex.getMessage(),
                "timestamp", Instant.now().toString()
        ));
    }
}
