package kz.courier.companyservice.repository;

import kz.courier.companyservice.model.Company;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;

public interface CompanyRepository extends JpaRepository<Company, UUID> {
    boolean existsByBin(String bin);
    boolean existsByBinAndIdNot(String bin, UUID id);

    @Query("""
        SELECT COUNT(c) > 0 FROM Company c
        WHERE LOWER(TRIM(c.name)) = LOWER(TRIM(:name))
        """)
    boolean existsByNormalizedName(@Param("name") String name);

    @Query("""
        SELECT COUNT(c) > 0 FROM Company c
        WHERE LOWER(TRIM(c.name)) = LOWER(TRIM(:name))
          AND c.id <> :id
        """)
    boolean existsByNormalizedNameAndIdNot(@Param("name") String name, @Param("id") UUID id);

    @Query("""
        SELECT c FROM Company c
        WHERE (:search IS NULL OR
               LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%')) OR
               c.bin LIKE CONCAT('%', :search, '%'))
        """)
    Page<Company> search(@Param("search") String search, Pageable pageable);
}
