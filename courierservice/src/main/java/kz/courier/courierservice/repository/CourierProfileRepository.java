package kz.courier.courierservice.repository;

import kz.courier.courierservice.entity.CourierProfile;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CourierProfileRepository extends JpaRepository<CourierProfile, UUID> {

    boolean existsByUserId(UUID userId);

    @EntityGraph(attributePaths = "schedules")
    Optional<CourierProfile> findWithSchedulesById(UUID id);

    @EntityGraph(attributePaths = "schedules")
    Optional<CourierProfile> findWithSchedulesByUserId(UUID userId);
}
