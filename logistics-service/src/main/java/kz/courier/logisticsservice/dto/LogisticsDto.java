package kz.courier.logisticsservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import kz.courier.logisticsservice.entity.AssignmentStatus;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * All request/response DTOs for the Logistics Service, grouped as nested classes
 * to keep the package clean and avoid name collisions.
 */
public final class LogisticsDto {

    private LogisticsDto() {}

    // =========================================================================
    //  Assignment DTOs
    // =========================================================================

    /** Request body for POST /assignments */
    public record CreateAssignmentRequest(
            @NotNull UUID orderId,
            @NotNull UUID courierId,
            UUID         assignedBy,    // null → system-assigned
            Integer      etaMinutes
    ) {}

    /** Request body for PATCH /assignments/{id}/status */
    public record UpdateStatusRequest(
            @NotNull AssignmentStatus newStatus,
            UUID changedBy,
            String reason              // mandatory when CANCELLED / REJECTED / FAILED
    ) {}

    /** Full assignment response */
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssignmentResponse(
            UUID             id,
            UUID             orderId,
            UUID             courierId,
            UUID             assignedBy,
            AssignmentStatus assignmentStatus,
            OffsetDateTime   assignedAt,
            OffsetDateTime   acceptedAt,
            OffsetDateTime   pickedUpAt,
            OffsetDateTime   deliveredAt,
            OffsetDateTime   cancelledAt,
            Integer          etaMinutes,
            Integer          actualDurationMinutes,
            String           rejectionReason,
            String           cancellationReason,
            OffsetDateTime   createdAt,
            OffsetDateTime   updatedAt
    ) {}

    /** Paginated list wrapper */
    @Builder
    public record PagedAssignments(
            List<AssignmentResponse> content,
            int  currentPage,
            int  pageSize,
            long totalItems,
            int  totalPages
    ) {}

    /** Auto-assignment result with ranking diagnostics for dispatch transparency. */
    @Builder
    public record AutoAssignResponse(
            AssignmentResponse assigned,
            AutoAssignedCourier selectedCourier,
            int scannedCouriers,
            int eligibleCouriers,
            double searchRadiusMeters,
            OffsetDateTime evaluatedAt
    ) {}

    /** Explains why the chosen courier won the automatic dispatch score. */
    @Builder
    public record AutoAssignedCourier(
            UUID courierId,
            Double distanceMeters,
            Integer etaMinutes,
            Double distanceScore,
            Double freshnessScore,
            Double totalScore,
            OffsetDateTime locationUpdatedAt
    ) {}

    /** Single audit history entry */
    @Builder
    public record HistoryEntry(
            Long             id,
            UUID             assignmentId,
            AssignmentStatus oldStatus,
            AssignmentStatus newStatus,
            UUID             changedBy,
            String           reason,
            OffsetDateTime   changedAt
    ) {}

    // =========================================================================
    //  Courier Location DTOs
    // =========================================================================

    /** Request body for PUT /couriers/me/location */
    public record UpdateLocationRequest(
            @NotNull
            @DecimalMin("-90.0") @DecimalMax("90.0")
            Double latitude,

            @NotNull
            @DecimalMin("-180.0") @DecimalMax("180.0")
            Double longitude,

            @NotNull Boolean isOnline
    ) {}

    /** Request body for PATCH /couriers/me/online */
    public record UpdateOnlineStatusRequest(
            @NotNull Boolean isOnline
    ) {}

    /** Courier location response */
    @Builder
    public record CourierLocationResponse(
            UUID           courierId,
            Double         latitude,
            Double         longitude,
            Boolean        isOnline,
            OffsetDateTime updatedAt
    ) {}

    /** Nearby courier entry — includes computed distance */
    @Builder
    public record NearbyCourierResponse(
            UUID   courierId,
            Double latitude,
            Double longitude,
            Double distanceMeters,
            Boolean isOnline,
            OffsetDateTime updatedAt
    ) {}

    /** Query params for GET /couriers/nearby */
    public record NearbyQuery(
            @NotNull
            @DecimalMin("-90.0") @DecimalMax("90.0")
            Double lat,

            @NotNull
            @DecimalMin("-180.0") @DecimalMax("180.0")
            Double lng,

            @Positive double radiusMeters,  // default applied in controller
            @Positive int    limit          // default applied in controller
    ) {}

    // =========================================================================
    //  Generic API envelope
    // =========================================================================

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiResponse<T>(
            boolean        success,
            T              data,
            String         errorCode,
            String         message,
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
