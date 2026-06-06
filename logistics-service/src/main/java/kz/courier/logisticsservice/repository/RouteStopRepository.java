package kz.courier.logisticsservice.repository;

import kz.courier.logisticsservice.entity.RouteStop;
import kz.courier.logisticsservice.entity.RouteStopStatus;
import kz.courier.logisticsservice.entity.RouteStopType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RouteStopRepository extends JpaRepository<RouteStop, UUID> {

    List<RouteStop> findAllByRouteIdOrderBySequenceNumberAsc(UUID routeId);

    Optional<RouteStop> findByRouteIdAndOrderIdAndStopTypeAndStatus(
            UUID routeId, UUID orderId, RouteStopType stopType, RouteStopStatus status);
}
