package kz.courier.logisticsservice.repository;

import kz.courier.logisticsservice.entity.CourierLocation;
import kz.courier.logisticsservice.dto.NearbycourierProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface CourierLocationRepository extends JpaRepository<CourierLocation, UUID> {

    List<CourierLocation> findAllByIsOnlineTrue();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM CourierLocation l WHERE l.courierId = :courierId")
    java.util.Optional<CourierLocation> lockByCourierId(@Param("courierId") UUID courierId);

    /**
     * Finds online couriers within {@code radiusMeters} of the given point,
     * ordered by ascending distance. Uses PostGIS {@code ST_DWithin} on the
     * stored GEOGRAPHY column for index-accelerated spatial filtering.
     *
     * @param lat          pickup latitude
     * @param lng          pickup longitude
     * @param radiusMeters search radius in metres
     * @param limit        maximum number of results
     */
    @Query(value = """

            SELECT
            cl.courier_id                                   AS courierId,
            cl.latitude,
            cl.longitude,
            cl.is_online                                    AS isOnline,
            cl.updated_at                                   AS updatedAt,
            ROUND(ST_Distance(
                cl.location_point,
                ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
            )::numeric, 2)                                  AS distanceMeters
        FROM courier_locations cl
        WHERE cl.is_online = TRUE
          AND ST_DWithin(
                cl.location_point,
                ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                :radiusMeters
              )
        ORDER BY distanceMeters ASC
        LIMIT :limit
        """, nativeQuery = true)
    List<NearbycourierProjection> findNearbyCouriers(
            @Param("lat")          double lat,
            @Param("lng")          double lng,
            @Param("radiusMeters") double radiusMeters,
            @Param("limit")        int    limit);

    /**
     * Upsert courier location. Uses PostgreSQL INSERT … ON CONFLICT to avoid
     * a separate SELECT + INSERT round-trip.
     */
    @Modifying
    @Query(value = """
        INSERT INTO courier_locations (courier_id, latitude, longitude, updated_at, is_online)
        VALUES (:courierId, :lat, :lng, :updatedAt, :isOnline)
        ON CONFLICT (courier_id) DO UPDATE
            SET latitude   = EXCLUDED.latitude,
                longitude  = EXCLUDED.longitude,
                updated_at = EXCLUDED.updated_at,
                is_online  = EXCLUDED.is_online
        """, nativeQuery = true)
    void upsertLocation(
            @Param("courierId")  UUID courierId,
            @Param("lat")        double lat,
            @Param("lng")        double lng,
            @Param("updatedAt")  OffsetDateTime updatedAt,
            @Param("isOnline")   boolean isOnline);

    /**
     * Updates only the online/offline flag without touching the coordinates.
     */
    @Modifying
    @Query(value = """
        INSERT INTO courier_locations (courier_id, latitude, longitude, updated_at, is_online)
        VALUES (:courierId, 0, 0, NOW(), :isOnline)
        ON CONFLICT (courier_id) DO UPDATE
            SET is_online  = EXCLUDED.is_online,
                updated_at = NOW()
        """, nativeQuery = true)
    void upsertOnlineStatus(
            @Param("courierId") UUID courierId,
            @Param("isOnline")  boolean isOnline);
}
