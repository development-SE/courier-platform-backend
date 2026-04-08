package kz.courier.logisticsservice.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Projection interface для нативного SQL-запроса findNearbyCouriers.
 * Spring Data JPA автоматически маппит результат запроса на этот интерфейс.
 */
public interface NearbycourierProjection {

    UUID getCourierId();

    Double getLatitude();

    Double getLongitude();

    Double getDistanceMeters();

    Boolean getIsOnline();

    Instant getUpdatedAt();
}
