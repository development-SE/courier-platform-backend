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

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssignmentRepository extends JpaRepository<CourierAssignment, UUID> {

    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(CAST(:orderId AS text)))", nativeQuery = true)
    Object lockOrderAssignmentMutex(@Param("orderId") String orderId);

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
               kz.courier.logisticsservice.entity.AssignmentStatus.FAILED,
               kz.courier.logisticsservice.entity.AssignmentStatus.REJECTED,
               kz.courier.logisticsservice.entity.AssignmentStatus.TIMED_OUT,
               kz.courier.logisticsservice.entity.AssignmentStatus.MANUAL_REQUIRED)
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
               kz.courier.logisticsservice.entity.AssignmentStatus.FAILED,
               kz.courier.logisticsservice.entity.AssignmentStatus.REJECTED,
               kz.courier.logisticsservice.entity.AssignmentStatus.TIMED_OUT,
               kz.courier.logisticsservice.entity.AssignmentStatus.MANUAL_REQUIRED)
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
               kz.courier.logisticsservice.entity.AssignmentStatus.TIMED_OUT,
               kz.courier.logisticsservice.entity.AssignmentStatus.MANUAL_REQUIRED)
        """)
    List<CourierAssignment> lockActiveAssignmentsByOrderId(@Param("orderId") UUID orderId);

    @Query("""
        SELECT a.courierId
        FROM CourierAssignment a
        WHERE a.orderId = :orderId
          AND a.courierId IS NOT NULL
          AND a.assignmentStatus IN
              (kz.courier.logisticsservice.entity.AssignmentStatus.REJECTED,
               kz.courier.logisticsservice.entity.AssignmentStatus.TIMED_OUT)
        """)
    List<UUID> findExcludedCourierIdsByOrderId(@Param("orderId") UUID orderId);

    @Query(value = """
        SELECT COUNT(DISTINCT courier_id)
        FROM courier_assignments
        WHERE order_id = :orderId
          AND courier_id IS NOT NULL
          AND assignment_status IN ('REJECTED', 'TIMED_OUT')
        """, nativeQuery = true)
    long countExcludedCourierIdsByOrderId(@Param("orderId") UUID orderId);

    @Query(value = """
        SELECT id
        FROM courier_assignments
        WHERE assignment_status = 'PENDING'
          AND assigned_at <= :olderThan
        ORDER BY assigned_at ASC
        LIMIT :limit
        """, nativeQuery = true)
    List<UUID> findTimedOutPendingOfferIds(
            @Param("olderThan") OffsetDateTime olderThan,
            @Param("limit") int limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT a
        FROM CourierAssignment a
        WHERE a.id = :id
          AND a.assignmentStatus = kz.courier.logisticsservice.entity.AssignmentStatus.PENDING
        """)
    Optional<CourierAssignment> lockPendingOfferById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT a FROM CourierAssignment a
        WHERE a.orderId = :orderId
          AND a.assignmentStatus = kz.courier.logisticsservice.entity.AssignmentStatus.MANUAL_REQUIRED
          AND a.resolvedAt IS NULL
        """)
    Optional<CourierAssignment> lockUnresolvedManualRequiredByOrderId(@Param("orderId") UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT a FROM CourierAssignment a
        WHERE a.id = :id
          AND a.assignmentStatus = kz.courier.logisticsservice.entity.AssignmentStatus.MANUAL_REQUIRED
          AND a.resolvedAt IS NULL
        """)
    Optional<CourierAssignment> lockUnresolvedManualRequiredById(@Param("id") UUID id);

    @Query("""
        SELECT a FROM CourierAssignment a
        WHERE a.assignmentStatus = kz.courier.logisticsservice.entity.AssignmentStatus.MANUAL_REQUIRED
          AND a.resolvedAt IS NULL
        """)
    Page<CourierAssignment> findUnresolvedManualRequired(Pageable pageable);

    @Query(value = """
        SELECT id
        FROM courier_assignments
        WHERE assignment_status = 'MANUAL_REQUIRED'
          AND resolved_at IS NULL
          AND failure_reason IN :temporaryReasons
          AND retry_count < :maxAttempts
          AND (next_retry_at IS NULL OR next_retry_at <= NOW())
        ORDER BY COALESCE(next_retry_at, created_at), created_at
        LIMIT :limit
        """, nativeQuery = true)
    List<UUID> findDueManualRequiredRetryIds(
            @Param("temporaryReasons") Collection<String> temporaryReasons,
            @Param("maxAttempts") int maxAttempts,
            @Param("limit") int limit);

    @Query(value = """
        SELECT id
        FROM courier_assignments
        WHERE assignment_status = 'MANUAL_REQUIRED'
          AND resolved_at IS NULL
          AND retry_count < :maxAttempts
        ORDER BY COALESCE(next_retry_at, created_at), created_at
        LIMIT :limit
        """, nativeQuery = true)
    List<UUID> findUnresolvedManualRequiredRetryIds(
            @Param("maxAttempts") int maxAttempts,
            @Param("limit") int limit);

    @Query(value = """
        SELECT id
        FROM courier_assignments
        WHERE assignment_status = 'MANUAL_REQUIRED'
          AND resolved_at IS NULL
        ORDER BY COALESCE(next_retry_at, created_at), created_at
        LIMIT :limit
        """, nativeQuery = true)
    List<UUID> findUnresolvedManualRequiredIds(@Param("limit") int limit);

    @Query(value = """
        SELECT a.order_id
        FROM courier_assignments a
        WHERE a.assignment_status IN ('TIMED_OUT', 'REJECTED')
          AND NOT EXISTS (
              SELECT 1
              FROM courier_assignments active
              WHERE active.order_id = a.order_id
                AND active.assignment_status NOT IN (
                    'DELIVERED', 'CANCELLED', 'FAILED',
                    'REJECTED', 'TIMED_OUT', 'MANUAL_REQUIRED'
                )
          )
          AND NOT EXISTS (
              SELECT 1
              FROM courier_assignments manual_required
              WHERE manual_required.order_id = a.order_id
                AND manual_required.assignment_status = 'MANUAL_REQUIRED'
                AND manual_required.resolved_at IS NULL
          )
        GROUP BY a.order_id
        ORDER BY MAX(a.updated_at) ASC
        LIMIT :limit
        """, nativeQuery = true)
    List<UUID> findOrdersNeedingReassignmentAfterTerminalOffer(@Param("limit") int limit);
}
