package kz.courier.courierservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import kz.courier.courierservice.entity.CourierType;
import kz.courier.courierservice.entity.EmploymentStatus;
import kz.courier.courierservice.entity.TransportType;
import lombok.Builder;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class CourierDto {

    private CourierDto() {}

    public record CreateCourierRequest(
            UUID userId,
            UUID companyId,
            CourierType courierType,
            EmploymentStatus employmentStatus,
            TransportType transportType,
            boolean isVerified,
            boolean canTakeOrders,
            @Min(1) @Max(20) Integer maxActiveOrders,
            String notes,
            @Valid List<ScheduleRequest> schedules
    ) {}

    public record UpdateCourierRequest(
            UUID companyId,
            CourierType courierType,
            EmploymentStatus employmentStatus,
            TransportType transportType,
            Boolean isVerified,
            Boolean canTakeOrders,
            @Min(1) @Max(20) Integer maxActiveOrders,
            String notes,
            @Valid List<ScheduleRequest> schedules
    ) {}

    public record ScheduleRequest(
            @NotNull DayOfWeek weekday,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            @NotBlank String timezone,
            boolean active
    ) {}

    @Builder
    public record CourierProfileResponse(
            UUID id,
            UUID companyId,
            CourierType courierType,
            EmploymentStatus employmentStatus,
            TransportType transportType,
            boolean isVerified,
            boolean canTakeOrders,
            int maxActiveOrders,
            String notes,
            List<ScheduleResponse> schedules,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    @Builder
    public record ScheduleResponse(
            Long id,
            DayOfWeek weekday,
            LocalTime startTime,
            LocalTime endTime,
            String timezone,
            boolean active
    ) {}

    @Builder
    public record EligibilityResponse(
            UUID courierId,
            boolean eligible,
            String reasonCode,
            String message,
            OffsetDateTime evaluatedAt,
            LocalDate evaluatedDate,
            LocalTime evaluatedLocalTime,
            boolean withinSchedule
    ) {}

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiResponse<T>(
            boolean success,
            T data,
            String errorCode,
            String message,
            OffsetDateTime timestamp
    ) {
        public static <T> ApiResponse<T> ok(T data) {
            return ApiResponse.<T>builder()
                    .success(true)
                    .data(data)
                    .timestamp(OffsetDateTime.now())
                    .build();
        }

        public static <T> ApiResponse<T> error(String code, String message) {
            return ApiResponse.<T>builder()
                    .success(false)
                    .errorCode(code)
                    .message(message)
                    .timestamp(OffsetDateTime.now())
                    .build();
        }
    }
}
