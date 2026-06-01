package kz.courier.logisticsservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "courier_assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourierAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "courier_id")
    private UUID courierId;

    @Column(name = "route_id")
    private UUID routeId;

    @Column(name = "assigned_by")
    private UUID assignedBy;

    @Column(name = "assignment_status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private AssignmentStatus assignmentStatus;

    @Column(name = "assigned_at", nullable = false)
    private OffsetDateTime assignedAt;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    @Column(name = "picked_up_at")
    private OffsetDateTime pickedUpAt;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "eta_minutes")
    private Integer etaMinutes;

    @Column(name = "actual_duration_minutes")
    private Integer actualDurationMinutes;

    @Column(name = "score")
    private Double score;

    @Column(name = "demand_units")
    private Integer demandUnits;

    @Column(name = "assignment_policy", length = 30)
    @Enumerated(EnumType.STRING)
    private AssignmentPolicy assignmentPolicy;

    @Column(name = "failure_reason", length = 50)
    @Enumerated(EnumType.STRING)
    private AssignmentFailureReason failureReason;

    @Column(name = "failure_message")
    private String failureMessage;

    @Column(name = "scanned_candidates")
    private Integer scannedCandidates;

    @Column(name = "eligible_candidates")
    private Integer eligibleCandidates;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "last_retry_at")
    private OffsetDateTime lastRetryAt;

    @Column(name = "next_retry_at")
    private OffsetDateTime nextRetryAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "resolved_assignment_id")
    private UUID resolvedAssignmentId;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
