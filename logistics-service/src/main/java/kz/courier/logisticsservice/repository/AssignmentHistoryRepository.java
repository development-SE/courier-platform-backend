package kz.courier.logisticsservice.repository;

import kz.courier.logisticsservice.entity.AssignmentHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AssignmentHistoryRepository extends JpaRepository<AssignmentHistory, Long> {

    /**
     * Возвращает полную историю изменений назначения, отсортированную по времени (от старого к новому).
     */
    List<AssignmentHistory> findAllByAssignmentIdOrderByChangedAtAsc(UUID assignmentId);

}