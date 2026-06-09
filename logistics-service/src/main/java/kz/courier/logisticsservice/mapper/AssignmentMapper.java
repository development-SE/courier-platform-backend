package kz.courier.logisticsservice.mapper;

import kz.courier.logisticsservice.dto.LogisticsDto;
import kz.courier.logisticsservice.dto.NearbycourierProjection;
import kz.courier.logisticsservice.entity.AssignmentHistory;
import kz.courier.logisticsservice.entity.CourierAssignment;
import kz.courier.logisticsservice.entity.CourierLocation;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Component
public class AssignmentMapper {

    public LogisticsDto.AssignmentResponse toResponse(CourierAssignment a) {
        return toResponse(a, null);
    }

    public LogisticsDto.AssignmentResponse toResponse(CourierAssignment a, String transportType) {
        return LogisticsDto.AssignmentResponse.builder()
                .id(a.getId())
                .orderId(a.getOrderId())
                .courierId(a.getCourierId())
                .routeId(a.getRouteId())
                .assignedBy(a.getAssignedBy())
                .assignmentStatus(a.getAssignmentStatus())
                .assignedAt(a.getAssignedAt())
                .acceptedAt(a.getAcceptedAt())
                .pickedUpAt(a.getPickedUpAt())
                .deliveredAt(a.getDeliveredAt())
                .cancelledAt(a.getCancelledAt())
                .etaMinutes(a.getEtaMinutes())
                .actualDurationMinutes(a.getActualDurationMinutes())
                .score(a.getScore())
                .demandUnits(a.getDemandUnits())
                .assignmentPolicy(a.getAssignmentPolicy() != null ? a.getAssignmentPolicy().name() : null)
                .failureReason(a.getFailureReason())
                .failureMessage(a.getFailureMessage())
                .scannedCandidates(a.getScannedCandidates())
                .eligibleCandidates(a.getEligibleCandidates())
                .retryCount(a.getRetryCount())
                .lastRetryAt(a.getLastRetryAt())
                .nextRetryAt(a.getNextRetryAt())
                .resolvedAt(a.getResolvedAt())
                .resolvedAssignmentId(a.getResolvedAssignmentId())
                .rejectionReason(a.getRejectionReason())
                .cancellationReason(a.getCancellationReason())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .transportType(transportType)
                .build();
    }

    public LogisticsDto.PagedAssignments toPagedResponse(Page<CourierAssignment> page) {
        return LogisticsDto.PagedAssignments.builder()
                .content(page.getContent().stream().map(this::toResponse).toList())
                .currentPage(page.getNumber() + 1)
                .pageSize(page.getSize())
                .totalItems(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .build();
    }

    public LogisticsDto.ManualRequiredAssignmentResponse toManualRequiredResponse(CourierAssignment a) {
        return LogisticsDto.ManualRequiredAssignmentResponse.builder()
                .assignmentId(a.getId())
                .orderId(a.getOrderId())
                .assignmentStatus(a.getAssignmentStatus())
                .failureReason(a.getFailureReason())
                .failureMessage(a.getFailureMessage())
                .demandUnits(a.getDemandUnits())
                .scannedCandidates(a.getScannedCandidates())
                .eligibleCandidates(a.getEligibleCandidates())
                .retryCount(a.getRetryCount())
                .lastRetryAt(a.getLastRetryAt())
                .nextRetryAt(a.getNextRetryAt())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }

    public LogisticsDto.PagedManualRequiredAssignments toPagedManualRequiredResponse(Page<CourierAssignment> page) {
        return LogisticsDto.PagedManualRequiredAssignments.builder()
                .content(page.getContent().stream().map(this::toManualRequiredResponse).toList())
                .currentPage(page.getNumber() + 1)
                .pageSize(page.getSize())
                .totalItems(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .build();
    }

    public LogisticsDto.HistoryEntry toHistoryEntry(AssignmentHistory h) {
        return LogisticsDto.HistoryEntry.builder()
                .id(h.getId())
                .assignmentId(h.getAssignmentId())
                .oldStatus(h.getOldStatus())
                .newStatus(h.getNewStatus())
                .changedBy(h.getChangedBy())
                .reason(h.getReason())
                .changedAt(h.getChangedAt())
                .build();
    }

    public LogisticsDto.CourierLocationResponse toLocationResponse(CourierLocation loc) {
        return toLocationResponse(loc, null);
    }

    public LogisticsDto.CourierLocationResponse toLocationResponse(CourierLocation loc, String transportType) {
        return LogisticsDto.CourierLocationResponse.builder()
                .courierId(loc.getCourierId())
                .latitude(loc.getLatitude())
                .longitude(loc.getLongitude())
                .isOnline(loc.getIsOnline())
                .updatedAt(loc.getUpdatedAt())
                .transportType(transportType)
                .build();
    }

    public List<LogisticsDto.NearbyCourierResponse> toNearbyCourierResponse(
            List<NearbycourierProjection> projections) {
        return projections.stream()
                .map(p -> LogisticsDto.NearbyCourierResponse.builder()
                        .courierId(p.getCourierId())
                        .latitude(p.getLatitude())
                        .longitude(p.getLongitude())
                        .distanceMeters(p.getDistanceMeters())
                        .isOnline(p.getIsOnline())
                        .updatedAt(toUtcOffsetDateTime(p.getUpdatedAt()))
                        .build())
                .toList();
    }

    private OffsetDateTime toUtcOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
