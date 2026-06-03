package kz.courier.logisticsservice.repository;

import jakarta.persistence.LockModeType;
import kz.courier.logisticsservice.entity.CourierRoute;
import kz.courier.logisticsservice.entity.RouteStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CourierRouteRepository extends JpaRepository<CourierRoute, UUID> {

    Optional<CourierRoute> findByCourierIdAndStatus(UUID courierId, RouteStatus status);

    long countByStatus(RouteStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT r FROM CourierRoute r
        WHERE r.id = :routeId
        """)
    Optional<CourierRoute> lockById(@Param("routeId") UUID routeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT r FROM CourierRoute r
        WHERE r.courierId = :courierId
          AND r.status = kz.courier.logisticsservice.entity.RouteStatus.ACTIVE
        """)
    Optional<CourierRoute> lockActiveRouteByCourierId(@Param("courierId") UUID courierId);
}
