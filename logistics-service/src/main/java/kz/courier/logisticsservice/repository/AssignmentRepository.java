package kz.courier.logisticsservice.repository;

import kz.courier.logisticsservice.entity.AssignmentStatus;
import kz.courier.logisticsservice.entity.CourierAssignment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    boolean existsByOrderIdAndAssignmentStatusNotIn(UUID orderId, Iterable<AssignmentStatus> statuses);
}