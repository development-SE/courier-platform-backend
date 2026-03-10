package kz.courier.companyservice.repository;

import kz.courier.companyservice.model.Employee;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {
    boolean existsByEmail(String email);
    boolean existsByEmailAndIdNot(String email, UUID id);
    Page<Employee> findByCompanyId(UUID companyId, Pageable pageable);

    @Query("""
        SELECT e FROM Employee e
        WHERE (:companyId IS NULL OR e.companyId = :companyId)
          AND (:search IS NULL OR
               LOWER(e.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR
               LOWER(e.lastName)  LIKE LOWER(CONCAT('%', :search, '%')) OR
               LOWER(e.email)     LIKE LOWER(CONCAT('%', :search, '%')))
        """)
    Page<Employee> search(@Param("companyId") UUID companyId,
                          @Param("search") String search,
                          Pageable pageable);
}