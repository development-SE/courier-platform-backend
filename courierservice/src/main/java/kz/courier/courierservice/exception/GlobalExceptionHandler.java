package kz.courier.courierservice.exception;

import jakarta.servlet.http.HttpServletRequest;
import kz.courier.common.error.FieldErrorDetail;
import kz.courier.common.error.StandardErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    @ExceptionHandler(CourierNotFoundException.class)
    public ResponseEntity<StandardErrorResponse> handleNotFound(
            CourierNotFoundException ex,
            HttpServletRequest request) {
        return error(request, HttpStatus.NOT_FOUND, "COURIER_NOT_FOUND", ex.getMessage(), List.of());
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<StandardErrorResponse> handleBusiness(
            BusinessException ex,
            HttpServletRequest request) {
        String traceId = traceId(request);
        log.warn("Business rule violation code={} traceId={}", ex.getCode(), traceId);
        return error(request, HttpStatus.UNPROCESSABLE_ENTITY, ex.getCode(), ex.getMessage(), List.of(), traceId);
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<StandardErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request) {
        return error(request, HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", ex.getMessage(), List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<StandardErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {
        String parameterName = ex.getName() != null ? ex.getName() : "request parameter";
        String message = "Invalid value for " + parameterName;
        if (ex.getRequiredType() != null && java.time.OffsetDateTime.class.equals(ex.getRequiredType())) {
            message = "Invalid OffsetDateTime for " + parameterName
                    + ". Use ISO-8601 format, for example 2026-04-27T10:00:00%2B05:00";
        }
        return error(request, HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<StandardErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {
        String supported = ex.getSupportedHttpMethods() == null || ex.getSupportedHttpMethods().isEmpty()
                ? ""
                : ex.getSupportedHttpMethods().stream()
                        .map(method -> method.name())
                        .collect(Collectors.joining(", "));

        String message = supported.isBlank()
                ? "HTTP method is not supported for this endpoint"
                : "HTTP method is not supported. Allowed methods: " + supported;

        log.warn("Method not allowed path={} method={} supported={}", request.getRequestURI(),
                ex.getMethod(), supported);

        return error(request, HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", message, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<StandardErrorResponse> handleUnexpected(
            Exception ex,
            HttpServletRequest request) {
        String traceId = traceId(request);
        log.error("Unexpected error path={} traceId={}", request.getRequestURI(), traceId, ex);
        return error(request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", List.of(), traceId);
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
