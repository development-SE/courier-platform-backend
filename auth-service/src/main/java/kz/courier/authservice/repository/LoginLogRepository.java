package kz.courier.authservice.repository;

import kz.courier.authservice.model.LoginLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LoginLogRepository extends JpaRepository<LoginLog, UUID> {

}
