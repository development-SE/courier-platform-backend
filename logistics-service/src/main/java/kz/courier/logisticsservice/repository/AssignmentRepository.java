package kz.courier.logisticsservice.repository;

import kz.courier.logisticsservice.entity.AssignmentStatus;
import kz.courier.logisticsservice.entity.CourierAssignment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssignmentRepository extends JpaRepository<CourierAssignment, UUID> {

    Page<CourierAssignment> findAllByCourierId(UUID courierId, Pageable pageable);

    Page<CourierAssignment> findAllByOrderId(UUID orderId, Pageable pageable);

    Page<CourierAssignment> findAllByAssignmentStatus(AssignmentStatus status, Pageable pageable);

    @Query("""

            SELECT a FROM CourierAssignment a
        WHERE (:courierId IS NULL OR a.courierId = :courierId)
          AND (:orderId   IS NULL OR a.orderId   = :orderId)
          AND (:status    IS NULL OR a.assignmentStatus = :status)
        """)
    Page<CourierAssignment> findAllFiltered(
            @Param("courierId") UUID courierId,
            @Param("orderId")   UUID orderId,
            @Param("status")    AssignmentStatus status,
            Pageable pageable);

    /**
     * Active assignment for a courier — any non-terminal status.
     */
    @Query("""
        SELECT a FROM CourierAssignment a
        WHERE a.courierId = :courierId
          AND a.assignmentStatus NOT IN
              (kz.courier.logisticsservice.entity.AssignmentStatus.DELIVERED,
               kz.courier.logisticsservice.entity.AssignmentStatus.CANCELLED,
               kz.courier.logisticsservice.entity.AssignmentStatus.FAILED)
        ORDER BY a.assignedAt DESC
        """)
    Optional<CourierAssignment> findActiveAssignmentByCourierId(@Param("courierId") UUID courierId);

    @Query("""
        SELECT a.courierId
        FROM CourierAssignment a
        WHERE a.courierId IN :courierIds
          AND a.assignmentStatus NOT IN
              (kz.courier.logisticsservice.entity.AssignmentStatus.DELIVERED,
               kz.courier.logisticsservice.entity.AssignmentStatus.CANCELLED,
               kz.courier.logisticsservice.entity.AssignmentStatus.FAILED)
        """)
    List<UUID> findBusyCourierIds(@Param("courierIds") Collection<UUID> courierIds);

    boolean existsByOrderIdAndAssignmentStatusNotIn(UUID orderId, Iterable<AssignmentStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT a FROM CourierAssignment a
        WHERE a.orderId = :orderId
          AND a.assignmentStatus NOT IN
              (kz.courier.logisticsservice.entity.AssignmentStatus.DELIVERED,
               kz.courier.logisticsservice.entity.AssignmentStatus.CANCELLED,
               kz.courier.logisticsservice.entity.AssignmentStatus.FAILED,
               kz.courier.logisticsservice.entity.AssignmentStatus.REJECTED,
               kz.courier.logisticsservice.entity.AssignmentStatus.MANUAL_REQUIRED)
        """)
    List<CourierAssignment> lockActiveAssignmentsByOrderId(@Param("orderId") UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT a FROM CourierAssignment a
        WHERE a.orderId = :orderId
          AND a.assignmentStatus = kz.courier.logisticsservice.entity.AssignmentStatus.MANUAL_REQUIRED
          AND a.resolvedAt IS NULL
        """)
    Optional<CourierAssignment> lockUnresolvedManualRequiredByOrderId(@Param("orderId") UUID orderId);

    @Query("""
        SELECT a FROM CourierAssignment a
        WHERE a.assignmentStatus = kz.courier.logisticsservice.entity.AssignmentStatus.MANUAL_REQUIRED
          AND a.resolvedAt IS NULL
        """)
    Page<CourierAssignment> findUnresolvedManualRequired(Pageable pageable);

    @Query(value = """
        SELECT *
        FROM courier_assignments
        WHERE assignment_status = 'MANUAL_REQUIRED'
          AND resolved_at IS NULL
          AND failure_reason IN :temporaryReasons
          AND retry_count < :maxAttempts
          AND (next_retry_at IS NULL OR next_retry_at <= NOW())
        ORDER BY COALESCE(next_retry_at, created_at), created_at
        FOR UPDATE SKIP LOCKED
        LIMIT :limit
        """, nativeQuery = true)
    List<CourierAssignment> lockDueManualRequiredRetries(
            @Param("temporaryReasons") Collection<String> temporaryReasons,
            @Param("maxAttempts") int maxAttempts,
            @Param("limit") int limit);
}
