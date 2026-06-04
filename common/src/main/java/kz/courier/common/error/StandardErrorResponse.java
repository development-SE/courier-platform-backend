package kz.courier.common.error;

import java.time.Instant;
import java.util.List;

public record StandardErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        String traceId,
        List<FieldErrorDetail> fieldErrors
) {
}
