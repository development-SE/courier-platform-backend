package kz.courier.courierservice.repository;

import kz.courier.courierservice.entity.CourierDocument;
import kz.courier.courierservice.entity.DocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CourierDocumentRepository extends JpaRepository<CourierDocument, UUID> {
    List<CourierDocument> findByCourierId(UUID courierId);
    Optional<CourierDocument> findByCourierIdAndDocumentType(UUID courierId, DocumentType type);
}
