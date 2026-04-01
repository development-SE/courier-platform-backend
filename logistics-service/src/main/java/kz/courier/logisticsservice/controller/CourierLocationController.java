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
 * PUT    /couriers/{courierId}/location    — upsert courier coordinates + online flag
 * GET    /couriers/{courierId}/location    — get current location of a courier
 * PATCH  /couriers/{courierId}/online      — toggle online/offline without changing coords
 * GET    /couriers/nearby                  — find online couriers within a radius (PostGIS)
 * </pre>
 *
 * <p>The {@code /couriers/nearby} endpoint is intended for internal use by the
 * auto-assignment algorithm and is typically called only by the logistics service
 * itself or a privileged admin/system role.
 */
@RestController
@RequestMapping("/couriers")
@RequiredArgsConstructor
public class CourierLocationController {

    private static final double DEFAULT_RADIUS_METERS = 5_000.0;
    private static final int    DEFAULT_LIMIT          = 10;

    private final LogisticsService service;

    // ── Location upsert ───────────────────────────────────────────────────────

    /**
     * Creates or updates the courier's location and online flag atomically.
     * Uses a PostgreSQL {@code INSERT … ON CONFLICT DO UPDATE} under the hood,
     * so this is safe to call at high frequency from a mobile device.
     */
    @PutMapping("/{courierId}/location")
    public ResponseEntity<LogisticsDto.ApiResponse<LogisticsDto.CourierLocationResponse>> updateLocation(
            @PathVariable UUID courierId,
            @Valid @RequestBody LogisticsDto.UpdateLocationRequest req) {

        return ResponseEntity.ok(
                LogisticsDto.ApiResponse.ok(service.updateLocation(courierId, req)));
    }

    // ── Location read ─────────────────────────────────────────────────────────

    @GetMapping("/{courierId}/location")
    public ResponseEntity<LogisticsDto.ApiResponse<LogisticsDto.CourierLocationResponse>> getLocation(
            @PathVariable UUID courierId) {

        return ResponseEntity.ok(
                LogisticsDto.ApiResponse.ok(service.getLocation(courierId)));
    }

    // ── Online status ─────────────────────────────────────────────────────────

    /**
     * Toggles the courier's online/offline status without overwriting coordinates.
     * Useful for the mobile app's "go online / go offline" toggle.
     */
    @PatchMapping("/{courierId}/online")
    public ResponseEntity<LogisticsDto.ApiResponse<LogisticsDto.CourierLocationResponse>> setOnline(
            @PathVariable UUID courierId,
            @Valid @RequestBody LogisticsDto.UpdateOnlineStatusRequest req) {

        return ResponseEntity.ok(
                LogisticsDto.ApiResponse.ok(service.updateOnlineStatus(courierId, req)));
    }

    // ── Nearby search ─────────────────────────────────────────────────────────

    /**
     * Returns online couriers within {@code radiusMeters} of the given coordinates,
     * sorted by ascending distance (closest first).
     *
     * <p>Query params:
     * <ul>
     *   <li>{@code lat}          – reference latitude (required)</li>
     *   <li>{@code lng}          – reference longitude (required)</li>
     *   <li>{@code radiusMeters} – search radius in metres (default 5 000)</li>
     *   <li>{@code limit}        – max results (default 10)</li>
     * </ul>
     */
    @GetMapping("/nearby")
    public ResponseEntity<LogisticsDto.ApiResponse<List<LogisticsDto.NearbyCourierResponse>>> nearby(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "5000.0") double radiusMeters,
            @RequestParam(defaultValue = "10")     int    limit) {

        double safeRadius = radiusMeters > 0 ? radiusMeters : DEFAULT_RADIUS_METERS;
        int    safeLimit  = limit > 0        ? limit        : DEFAULT_LIMIT;

        return ResponseEntity.ok(
                LogisticsDto.ApiResponse.ok(
                        service.findNearbyCouriers(lat, lng, safeRadius, safeLimit)));
    }
}