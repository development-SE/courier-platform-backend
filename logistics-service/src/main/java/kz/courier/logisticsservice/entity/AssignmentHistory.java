package kz.courier.logisticsservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "assignment_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssignmentHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assignment_id", nullable = false)
    private UUID assignmentId;

    @Column(name = "old_status", length = 30)
    @Enumerated(EnumType.STRING)
    private AssignmentStatus oldStatus;

    @Column(name = "new_status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private AssignmentStatus newStatus;

    @Column(name = "changed_by")
    private UUID changedBy;

    @Column(name = "reason")
    private String reason;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;
}