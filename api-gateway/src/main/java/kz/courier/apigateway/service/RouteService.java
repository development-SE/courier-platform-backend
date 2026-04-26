package kz.courier.apigateway.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import kz.courier.apigateway.dto.request.route.RouteCalculationRequest;
import kz.courier.apigateway.dto.request.route.RoutePointRequest;
import kz.courier.apigateway.dto.response.RouteCalculationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class RouteService {

    private static final String GOOGLE_ROUTES_URL = "https://routes.googleapis.com";
    private static final String GOOGLE_ROUTES_PATH = "/directions/v2:computeRoutes";
    private static final String GOOGLE_FIELD_MASK =
            "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline";
    private static final long FALLBACK_DURATION_SECONDS = 50L * 60L;
    private static final long FALLBACK_MIN_DISTANCE_METERS = 4_200L;

    private final WebClient.Builder webClientBuilder;

    @Value("${GOOGLE_MAPS_API_KEY:}")
    private String googleMapsApiKey;

    public Mono<RouteCalculationResponse> calculate(RouteCalculationRequest request) {
        if (googleMapsApiKey == null || googleMapsApiKey.isBlank()) {
            log.warn("[RouteService] GOOGLE_MAPS_API_KEY is not configured. Returning fallback route.");
            return Mono.just(buildFallbackResponse(request));
        }

        GoogleRouteRequest payload = new GoogleRouteRequest(
                new LocationWrapper(new LatLngWrapper(request.origin().lat(), request.origin().lng())),
                new LocationWrapper(new LatLngWrapper(request.destination().lat(), request.destination().lng())),
                "DRIVE",
                "TRAFFIC_AWARE",
                false,
                "ru",
                "METRIC"
        );

        return webClientBuilder
                .baseUrl(GOOGLE_ROUTES_URL)
                .build()
                .post()
                .uri(GOOGLE_ROUTES_PATH)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("X-Goog-Api-Key", googleMapsApiKey)
                .header("X-Goog-FieldMask", GOOGLE_FIELD_MASK)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(GoogleRoutesResponse.class)
                .map(response -> mapGoogleResponse(response, request))
                .onErrorResume(error -> {
                    log.warn("[RouteService] Google Routes API failed. Falling back to mock route: {}",
                            error.getMessage());
                    return Mono.just(buildFallbackResponse(request));
                });
    }

    private RouteCalculationResponse mapGoogleResponse(
            GoogleRoutesResponse response,
            RouteCalculationRequest request
    ) {
        if (response == null || response.routes() == null || response.routes().isEmpty()) {
            return buildFallbackResponse(request);
        }

        GoogleRoute route = response.routes().getFirst();
        String encodedPolyline = route.polyline() != null ? route.polyline().encodedPolyline() : null;
        if (encodedPolyline == null || encodedPolyline.isBlank()) {
            return buildFallbackResponse(request);
        }

        long distanceMeters = route.distanceMeters() != null
                ? route.distanceMeters()
                : estimateDistanceMeters(buildFallbackRoutePoints(request));
        long durationSeconds = parseDurationSeconds(route.duration());

        if (durationSeconds <= 0) {
            durationSeconds = FALLBACK_DURATION_SECONDS;
        }

        return new RouteCalculationResponse(distanceMeters, durationSeconds, encodedPolyline);
    }

    private RouteCalculationResponse buildFallbackResponse(RouteCalculationRequest request) {
        List<RoutePointRequest> fallbackPoints = buildFallbackRoutePoints(request);
        long estimatedDistance = Math.max(
                FALLBACK_MIN_DISTANCE_METERS,
                estimateDistanceMeters(fallbackPoints)
        );

        return new RouteCalculationResponse(
                estimatedDistance,
                FALLBACK_DURATION_SECONDS,
                encodePolyline(fallbackPoints)
        );
    }

    private List<RoutePointRequest> buildFallbackRoutePoints(RouteCalculationRequest request) {
        RoutePointRequest origin = request.origin();
        RoutePointRequest destination = request.destination();

        double midLat = (origin.lat() + destination.lat()) / 2.0;
        double midLng = (origin.lng() + destination.lng()) / 2.0;
        double offsetLat = -(destination.lng() - origin.lng()) * 0.18;
        double offsetLng = (destination.lat() - origin.lat()) * 0.18;

        RoutePointRequest curvePoint = new RoutePointRequest(midLat + offsetLat, midLng + offsetLng);
        return List.of(origin, curvePoint, destination);
    }

    private long parseDurationSeconds(String durationValue) {
        if (durationValue == null || durationValue.isBlank()) {
            return 0L;
        }

        String sanitized = durationValue.endsWith("s")
                ? durationValue.substring(0, durationValue.length() - 1)
                : durationValue;
        try {
            return Math.round(Double.parseDouble(sanitized));
        } catch (NumberFormatException exception) {
            log.warn("[RouteService] Failed to parse duration '{}'", durationValue);
            return 0L;
        }
    }

    private long estimateDistanceMeters(List<RoutePointRequest> points) {
        double total = 0.0;
        for (int index = 0; index < points.size() - 1; index++) {
            total += haversineMeters(points.get(index), points.get(index + 1));
        }
        return Math.round(total);
    }

    private double haversineMeters(RoutePointRequest start, RoutePointRequest finish) {
        double earthRadius = 6_371_000.0;
        double latDistance = Math.toRadians(finish.lat() - start.lat());
        double lonDistance = Math.toRadians(finish.lng() - start.lng());
        double startLat = Math.toRadians(start.lat());
        double finishLat = Math.toRadians(finish.lat());

        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(startLat) * Math.cos(finishLat)
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadius * c;
    }

    private String encodePolyline(List<RoutePointRequest> points) {
        StringBuilder encoded = new StringBuilder();
        long previousLat = 0;
        long previousLng = 0;

        for (RoutePointRequest point : points) {
            long latitude = Math.round(point.lat() * 1e5);
            long longitude = Math.round(point.lng() * 1e5);

            encodeCoordinate(encoded, latitude - previousLat);
            encodeCoordinate(encoded, longitude - previousLng);

            previousLat = latitude;
            previousLng = longitude;
        }

        return encoded.toString();
    }

    private void encodeCoordinate(StringBuilder builder, long coordinateDelta) {
        long value = coordinateDelta < 0 ? ~(coordinateDelta << 1) : coordinateDelta << 1;
        while (value >= 0x20) {
            builder.append((char) ((0x20 | (value & 0x1f)) + 63));
            value >>= 5;
        }
        builder.append((char) (value + 63));
    }

    private record GoogleRouteRequest(
            LocationWrapper origin,
            LocationWrapper destination,
            String travelMode,
            String routingPreference,
            boolean computeAlternativeRoutes,
            String languageCode,
            String units
    ) {
    }

    private record LocationWrapper(LatLngWrapper location) {
    }

    private record LatLngWrapper(LatLng latLng) {
        private LatLngWrapper(Double latitude, Double longitude) {
            this(new LatLng(latitude, longitude));
        }
    }

    private record LatLng(Double latitude, Double longitude) {
    }

    private record GoogleRoutesResponse(List<GoogleRoute> routes) {
    }

    private record GoogleRoute(
            Long distanceMeters,
            String duration,
            GooglePolyline polyline
    ) {
    }

    private record GooglePolyline(String encodedPolyline) {
    }
}
