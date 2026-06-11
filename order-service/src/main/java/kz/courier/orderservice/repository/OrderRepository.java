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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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

    @Query(value = """
        SELECT o.* FROM orders o
        JOIN addresses pickup ON pickup.id = o.pickup_addr_id
        LEFT JOIN addresses delivery ON delivery.id = o.delivery_addr_id
        LEFT JOIN contacts pickup_c ON pickup_c.id = o.pickup_contact_id
        LEFT JOIN contacts recipient_c ON recipient_c.id = o.recipient_contact_id
        WHERE (CAST(:status AS text) IS NULL OR o.status = CAST(:status AS text))
          AND (CAST(:companyId AS uuid) IS NULL OR o.company_id = CAST(:companyId AS uuid))
          AND (CAST(:authorId AS uuid) IS NULL OR o.author_id = CAST(:authorId AS uuid))
          AND (CAST(:minAmount AS numeric) IS NULL OR o.total_amount >= CAST(:minAmount AS numeric))
          AND (CAST(:maxAmount AS numeric) IS NULL OR o.total_amount <= CAST(:maxAmount AS numeric))
          AND (CAST(:createdAfter AS timestamptz) IS NULL OR o.created_at >= CAST(:createdAfter AS timestamptz))
          AND (CAST(:createdBefore AS timestamptz) IS NULL OR o.created_at <= CAST(:createdBefore AS timestamptz))
          AND earth_box(
                ll_to_earth(CAST(:lat AS float8), CAST(:lng AS float8)),
                CAST(:radiusMeters AS float8)
              ) @> ll_to_earth(pickup.latitude, pickup.longitude)
          AND earth_distance(
                ll_to_earth(CAST(:lat AS float8), CAST(:lng AS float8)),
                ll_to_earth(pickup.latitude, pickup.longitude)
              ) <= CAST(:radiusMeters AS float8)
        ORDER BY earth_distance(
                   ll_to_earth(CAST(:lat AS float8), CAST(:lng AS float8)),
                   ll_to_earth(pickup.latitude, pickup.longitude)
                 ) ASC,
                 o.created_at DESC
        """,
        countQuery = """
        SELECT count(o.id) FROM orders o
        JOIN addresses pickup ON pickup.id = o.pickup_addr_id
        WHERE (CAST(:status AS text) IS NULL OR o.status = CAST(:status AS text))
          AND (CAST(:companyId AS uuid) IS NULL OR o.company_id = CAST(:companyId AS uuid))
          AND (CAST(:authorId AS uuid) IS NULL OR o.author_id = CAST(:authorId AS uuid))
          AND (CAST(:minAmount AS numeric) IS NULL OR o.total_amount >= CAST(:minAmount AS numeric))
          AND (CAST(:maxAmount AS numeric) IS NULL OR o.total_amount <= CAST(:maxAmount AS numeric))
          AND (CAST(:createdAfter AS timestamptz) IS NULL OR o.created_at >= CAST(:createdAfter AS timestamptz))
          AND (CAST(:createdBefore AS timestamptz) IS NULL OR o.created_at <= CAST(:createdBefore AS timestamptz))
          AND earth_box(
                ll_to_earth(CAST(:lat AS float8), CAST(:lng AS float8)),
                CAST(:radiusMeters AS float8)
              ) @> ll_to_earth(pickup.latitude, pickup.longitude)
          AND earth_distance(
                ll_to_earth(CAST(:lat AS float8), CAST(:lng AS float8)),
                ll_to_earth(pickup.latitude, pickup.longitude)
              ) <= CAST(:radiusMeters AS float8)
        """,
        nativeQuery = true)
    Page<Order> findAllNearby(
            @Param("status") String status,
            @Param("companyId") UUID companyId,
            @Param("authorId") UUID authorId,
            @Param("minAmount") BigDecimal minAmount,
            @Param("maxAmount") BigDecimal maxAmount,
            @Param("createdAfter") OffsetDateTime createdAfter,
            @Param("createdBefore") OffsetDateTime createdBefore,
            @Param("lat") Double lat,
            @Param("lng") Double lng,
            @Param("radiusMeters") Double radiusMeters,
            Pageable pageable
    );
}
