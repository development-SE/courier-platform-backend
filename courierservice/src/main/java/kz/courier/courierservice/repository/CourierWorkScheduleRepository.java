package kz.courier.courierservice.repository;

import kz.courier.courierservice.entity.CourierWorkSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourierWorkScheduleRepository extends JpaRepository<CourierWorkSchedule, Long> {
}
