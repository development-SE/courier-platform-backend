package kz.courier.courierservice.exception;

import jakarta.servlet.http.HttpServletRequest;
import kz.courier.courierservice.dto.CourierDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CourierNotFoundException.class)
    public ResponseEntity<CourierDto.ApiResponse<Void>> handleNotFound(CourierNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(CourierDto.ApiResponse.error("COURIER_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<CourierDto.ApiResponse<Void>> handleBusiness(BusinessException ex) {
        log.warn("Business rule violation [{}]: {}", ex.getCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(CourierDto.ApiResponse.error(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CourierDto.ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(CourierDto.ApiResponse.error("VALIDATION_ERROR", details));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<CourierDto.ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(CourierDto.ApiResponse.error("INVALID_ARGUMENT", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<CourierDto.ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String parameterName = ex.getName() != null ? ex.getName() : "request parameter";
        String message = "Invalid value for " + parameterName;
        if (ex.getRequiredType() != null && java.time.OffsetDateTime.class.equals(ex.getRequiredType())) {
            message = "Invalid OffsetDateTime for " + parameterName
                    + ". Use ISO-8601 format, for example 2026-04-27T10:00:00%2B05:00";
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(CourierDto.ApiResponse.error("INVALID_ARGUMENT", message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CourierDto.ApiResponse<Void>> handleUnexpected(
            Exception ex,
            HttpServletRequest request) {
        log.error("Unexpected error on path={}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(CourierDto.ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
