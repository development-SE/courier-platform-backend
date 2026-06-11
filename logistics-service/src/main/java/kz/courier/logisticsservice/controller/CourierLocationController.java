package kz.courier.logisticsservice.controller;

import jakarta.validation.Valid;
import kz.courier.logisticsservice.dto.LogisticsDto;
import kz.courier.logisticsservice.service.LogisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST API for courier real-time location and online-status management.
 *
 * <pre>
 * PUT    /couriers/me/location   - upsert caller's coordinates + online flag
 * GET    /couriers/me/location   - get caller's current location
 * PATCH  /couriers/me/online     - toggle caller online/offline without changing coords
 * GET    /couriers/nearby        - find online couriers within a radius (PostGIS)
 * </pre>
 *
 * <p>The controller deliberately avoids accepting a courier id for self-service
 * actions. Identity is resolved in the service layer from trusted gateway
 * headers, which prevents IDOR and keeps the controller thin.
 */
@RestController
@RequestMapping("/couriers")
@RequiredArgsConstructor
public class CourierLocationController {

    private static final double DEFAULT_RADIUS_METERS = 5_000.0;
    private static final int DEFAULT_LIMIT = 10;

    private final LogisticsService service;

    /**
     * Creates or updates the authenticated courier's location and online flag atomically.
     */
    @PutMapping("/me/location")
    public ResponseEntity<LogisticsDto.ApiResponse<LogisticsDto.CourierLocationResponse>> updateLocation(
            @Valid @RequestBody LogisticsDto.UpdateLocationRequest req) {

        return ResponseEntity.ok(
                LogisticsDto.ApiResponse.ok(service.updateMyLocation(req)));
    }

    // ── Location read ─────────────────────────────────────────────────────────

    @GetMapping("/{courierId}/location")
    public ResponseEntity<LogisticsDto.ApiResponse<LogisticsDto.CourierLocationResponse>> getLocation(
            @PathVariable UUID courierId) {

        return ResponseEntity.ok(
                LogisticsDto.ApiResponse.ok(service.getLocation(courierId)));
    }

    @GetMapping("/{courierId}")
    public ResponseEntity<LogisticsDto.ApiResponse<LogisticsDto.CourierDetailsResponse>> getCourierDetails(
            @PathVariable UUID courierId) {

        return ResponseEntity.ok(
                LogisticsDto.ApiResponse.ok(service.getCourierDetails(courierId)));
    }

    /**
     * Toggles the authenticated courier's online/offline status without overwriting coordinates.
     */
    @PatchMapping("/me/online")
    public ResponseEntity<LogisticsDto.ApiResponse<LogisticsDto.CourierLocationResponse>> setOnline(
            @Valid @RequestBody LogisticsDto.UpdateOnlineStatusRequest req) {

        return ResponseEntity.ok(
                LogisticsDto.ApiResponse.ok(service.updateMyOnlineStatus(req)));
    }

    /**
     * Returns online couriers within {@code radiusMeters} of the given coordinates,
     * sorted by ascending distance.
     */
    @GetMapping("/nearby")
    public ResponseEntity<LogisticsDto.ApiResponse<List<LogisticsDto.NearbyCourierResponse>>> nearby(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "5000.0") double radiusMeters,
            @RequestParam(defaultValue = "10") int limit) {

        double safeRadius = radiusMeters > 0 ? radiusMeters : DEFAULT_RADIUS_METERS;
        int safeLimit = limit > 0 ? limit : DEFAULT_LIMIT;

        return ResponseEntity.ok(
                LogisticsDto.ApiResponse.ok(
                        service.findNearbyCouriers(lat, lng, safeRadius, safeLimit)));
    }
}
