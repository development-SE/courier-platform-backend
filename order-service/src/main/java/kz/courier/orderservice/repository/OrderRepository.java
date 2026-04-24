package kz.courier.orderservice.repository;

import kz.courier.orderservice.model.Order;
import kz.courier.orderservice.model.OrderStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    @Override
    @EntityGraph(attributePaths = {
            "deliveryAddress",
            "recipientContact",
            "pickupAddress",
            "pickupContact"
    })
    Page<Order> findAll(Specification<Order> spec, Pageable pageable);

    /** Paginated fetch of all orders belonging to a specific author. */
    Page<Order> findAllByAuthorId(UUID authorId, Pageable pageable);

    /** All orders in a given status (e.g., for courier assignment logic). */
    List<Order> findAllByStatus(OrderStatus status);

    /**
     * Active (non-terminal) orders for a client; useful for client dashboard.
     */
    @Query("""
        SELECT o FROM Order o
        WHERE o.authorId = :authorId
          AND o.status NOT IN (
            kz.courier.orderservice.model.OrderStatus.DELIVERED,
            kz.courier.orderservice.model.OrderStatus.CANCELLED,
            kz.courier.orderservice.model.OrderStatus.REJECTED
          )
        """)
    List<Order> findActiveOrdersByAuthorId(@Param("authorId") UUID authorId);
}
