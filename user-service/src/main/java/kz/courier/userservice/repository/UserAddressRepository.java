package kz.courier.userservice.repository;

import kz.courier.userservice.model.UserAddress;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserAddressRepository extends JpaRepository<UserAddress, UUID> {

    Page<UserAddress> findByUserId(UUID userId, Pageable pageable);

    Optional<UserAddress> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserId(UUID userId);

    @Modifying
    @Query("UPDATE UserAddress a SET a.defaultAddress = false WHERE a.userId = :userId")
    void clearDefaultForUser(@Param("userId") UUID userId);
}
