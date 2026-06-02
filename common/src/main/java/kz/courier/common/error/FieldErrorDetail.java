package kz.courier.common.error;

public record FieldErrorDetail(
        String field,
        String message
) {
}
