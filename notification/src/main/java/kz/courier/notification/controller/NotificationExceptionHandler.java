package kz.courier.notification.controller;

import jakarta.servlet.http.HttpServletRequest;
import kz.courier.common.error.FieldErrorDetail;
import kz.courier.common.error.StandardErrorResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public class NotificationExceptionHandler {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<StandardErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request) {
        return error(request, HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", ex.getMessage(), List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<StandardErrorResponse> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        List<FieldErrorDetail> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorDetail(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return error(request, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", fieldErrors);
    }

    private ResponseEntity<StandardErrorResponse> error(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String message,
            List<FieldErrorDetail> fieldErrors) {
        String traceId = traceId(request);
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
