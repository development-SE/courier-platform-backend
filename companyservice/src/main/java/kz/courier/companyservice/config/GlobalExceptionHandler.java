package kz.courier.companyservice.config;

import jakarta.servlet.http.HttpServletRequest;
import kz.courier.common.error.FieldErrorDetail;
import kz.courier.common.error.StandardErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<StandardErrorResponse> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        List<FieldErrorDetail> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorDetail(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return error(request, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", fieldErrors);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<StandardErrorResponse> handleStatus(
            ResponseStatusException ex,
            HttpServletRequest request) {
        String message = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        return error(request, status, errorCode(message), message, List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<StandardErrorResponse> handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request) {
        return error(request, HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "You do not have permission to access this resource", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<StandardErrorResponse> handleGeneral(
            Exception ex,
            HttpServletRequest request) {
        String traceId = traceId(request);
        log.error("Unexpected error path={} traceId={}", request.getRequestURI(), traceId, ex);
        return error(request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", List.of(), traceId);
    }

    private String errorCode(String message) {
        if (message == null || message.isBlank()) {
            return "ERROR";
        }

        int separator = message.indexOf(':');
        if (separator <= 0) {
            return message;
        }

        return message.substring(0, separator).trim();
    }

    private ResponseEntity<StandardErrorResponse> error(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String message,
            List<FieldErrorDetail> fieldErrors) {
        return error(request, status, code, message, fieldErrors, traceId(request));
    }

    private ResponseEntity<StandardErrorResponse> error(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String message,
            List<FieldErrorDetail> fieldErrors,
            String traceId) {
        return ResponseEntity.status(status)
                .header(CORRELATION_HEADER, traceId)
                .body(new StandardErrorResponse(
                        Instant.now(),
                        status.value(),
                        status.getReasonPhrase(),
                        code,
                        message,
                        request.getRequestURI(),
                        traceId,
                        fieldErrors));
    }

    private String traceId(HttpServletRequest request) {
        String header = request.getHeader(CORRELATION_HEADER);
        if (header != null && !header.isBlank()) {
            return header;
        }
        String mdcTraceId = MDC.get("correlationId");
        return mdcTraceId != null && !mdcTraceId.isBlank() ? mdcTraceId : UUID.randomUUID().toString();
    }
}
