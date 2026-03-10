package kz.courier.companyservice.repository;

import kz.courier.companyservice.model.Address;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AddressRepository extends JpaRepository<Address, UUID> {
    Page<Address> findByCompanyId(UUID companyId, Pageable pageable);
}