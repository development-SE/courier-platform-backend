package kz.courier.courierservice.repository;

import kz.courier.courierservice.entity.CourierProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CourierProfileRepository extends JpaRepository<CourierProfile, UUID> {

    @EntityGraph(attributePaths = "schedules")
    Optional<CourierProfile> findWithSchedulesById(UUID id);

    @Query("SELECT c FROM CourierProfile c WHERE (:companyId IS NULL OR c.companyId = :companyId)")
    Page<CourierProfile> findAllFiltered(@Param("companyId") UUID companyId, Pageable pageable);
}
