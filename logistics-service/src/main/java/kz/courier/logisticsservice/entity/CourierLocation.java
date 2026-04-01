package kz.courier.logisticsservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Real-time courier location.
 *
 * <p>The {@code location_point} GEOGRAPHY column is database-generated (STORED),
 * so it is intentionally excluded from JPA mapping.
 * All PostGIS geo-queries are executed via native JPQL/SQL in the repository.
 */
@Entity
@Table(name = "courier_locations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourierLocation {

    @Id
    @Column(name = "courier_id")
    private UUID courierId;

    @Column(name = "latitude", nullable = false)
    private Double latitude;

    @Column(name = "longitude", nullable = false)
    private Double longitude;

    // location_point is a STORED GENERATED column — JPA must not touch it.
    // It is used only in native queries (ST_DWithin, ST_Distance, etc.)

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "is_online", nullable = false)
    private Boolean isOnline;
}