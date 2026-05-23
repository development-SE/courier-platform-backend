package kz.courier.orderservice.repository;

import jakarta.persistence.LockModeType;
import kz.courier.orderservice.model.DeliveryConfirmationCode;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeliveryConfirmationCodeRepository
        extends JpaRepository<DeliveryConfirmationCode, UUID> {

    @EntityGraph(attributePaths = "order")
    Optional<DeliveryConfirmationCode> findTopByOrder_IdAndConsumedFalseOrderByCreatedAtDesc(UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "order")
    Optional<DeliveryConfirmationCode> findFirstByOrder_IdAndConsumedFalseOrderByCreatedAtDesc(UUID orderId);
}
