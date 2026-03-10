package kz.courier.userservice.repository;

import kz.courier.userservice.model.Role;
import kz.courier.userservice.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByPhone(String phone);
    boolean existsByEmailAndIdNot(String email, UUID id);
    boolean existsByPhoneAndIdNot(String phone, UUID id);

    Page<User> findByRole(Role role, Pageable pageable);

    /**
     * Universal search + optional role filter.
     * Searches across first name, last name, email, phone.
     */
    @Query("""
        SELECT u FROM User u
        WHERE (:role IS NULL OR u.role = :role)
          AND (:search IS NULL OR
               LOWER(u.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR
               LOWER(u.lastName)  LIKE LOWER(CONCAT('%', :search, '%')) OR
               LOWER(u.email)     LIKE LOWER(CONCAT('%', :search, '%')) OR
               u.phone            LIKE CONCAT('%', :search, '%'))
        """)
    Page<User> search(@Param("role") Role role,
                      @Param("search") String search,
                      Pageable pageable);
}
