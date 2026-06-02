package kz.courier.logisticsservice.exception;

import jakarta.servlet.http.HttpServletRequest;
import kz.courier.common.error.FieldErrorDetail;
import kz.courier.common.error.StandardErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    @ExceptionHandler(AssignmentNotFoundException.class)
    public ResponseEntity<StandardErrorResponse> handleNotFound(
            AssignmentNotFoundException ex,
            HttpServletRequest request) {
        return error(request, HttpStatus.NOT_FOUND, "ASSIGNMENT_NOT_FOUND", ex.getMessage(), List.of());
    }

    @ExceptionHandler(LocationNotFoundException.class)
    public ResponseEntity<StandardErrorResponse> handleLocationNotFound(
            LocationNotFoundException ex,
            HttpServletRequest request) {
        return error(request, HttpStatus.NOT_FOUND, "LOCATION_NOT_FOUND", ex.getMessage(), List.of());
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<StandardErrorResponse> handleBusiness(
            BusinessException ex,
            HttpServletRequest request) {
        String traceId = traceId(request);
        log.warn("Business rule violation code={} traceId={}", ex.getCode(), traceId);
        return error(request, statusForBusinessCode(ex.getCode()), ex.getCode(), ex.getMessage(), List.of(), traceId);
    }

    private HttpStatus statusForBusinessCode(String code) {
        return switch (code) {
            case "UNAUTHENTICATED" -> HttpStatus.UNAUTHORIZED;
            case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "DUPLICATE_ASSIGNMENT",
                 "TERMINAL_STATUS",
                 "OTP_ALREADY_ACTIVE" -> HttpStatus.CONFLICT;
            case "INVALID_ARGUMENT",
                 "INVALID_STATUS",
                 "INVALID_TRANSITION",
                 "ORDER_NOT_ASSIGNABLE",
                 "COURIER_NOT_FEASIBLE",
                 "COURIER_LOCATION_MISSING",
                 "DEDICATED_FLOW_REQUIRED" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
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
