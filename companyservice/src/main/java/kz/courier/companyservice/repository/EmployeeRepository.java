package kz.courier.companyservice.repository;

import kz.courier.companyservice.model.Employee;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {
    boolean existsByEmail(String email);
    boolean existsByEmailAndIdNot(String email, UUID id);
    boolean existsByCompanyIdAndRole(UUID companyId, String role);
    Page<Employee> findByCompanyId(UUID companyId, Pageable pageable);
    Optional<Employee> findFirstByCompanyIdAndRole(UUID companyId, String role);

    @Query("""
        SELECT e FROM Employee e
        WHERE (:companyId IS NULL OR e.companyId = :companyId)
          AND (:role IS NULL OR e.role = :role)
          AND (:search IS NULL OR
               LOWER(e.firstName) LIKE CONCAT('%', LOWER(CAST(:search AS string)), '%') OR
               LOWER(e.lastName)  LIKE CONCAT('%', LOWER(CAST(:search AS string)), '%') OR
               LOWER(e.email)     LIKE CONCAT('%', LOWER(CAST(:search AS string)), '%'))
        """)
    Page<Employee> search(@Param("companyId") UUID companyId,
                          @Param("role") String role,
                          @Param("search") String search,
                          Pageable pageable);
}
