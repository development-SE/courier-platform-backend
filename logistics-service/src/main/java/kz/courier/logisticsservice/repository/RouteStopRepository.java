package kz.courier.logisticsservice.repository;

import kz.courier.logisticsservice.entity.RouteStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RouteStopRepository extends JpaRepository<RouteStop, UUID> {

    List<RouteStop> findAllByRouteIdOrderBySequenceNumberAsc(UUID routeId);

    java.util.Optional<RouteStop> findFirstByRouteIdAndStatusOrderBySequenceNumberAsc(
            UUID routeId,
            kz.courier.logisticsservice.entity.RouteStopStatus status);

    java.util.Optional<RouteStop> findByRouteIdAndOrderIdAndStopTypeAndStatus(
            UUID routeId,
            UUID orderId,
            kz.courier.logisticsservice.entity.RouteStopType stopType,
            kz.courier.logisticsservice.entity.RouteStopStatus status);
}
