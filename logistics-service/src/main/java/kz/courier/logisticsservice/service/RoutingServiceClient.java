package kz.courier.logisticsservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class RoutingServiceClient {

    private final ObjectMapper objectMapper;

    @Value("${routing.osrm.base-url:http://router.project-osrm.org}")
    private String osrmBaseUrl;

    private static final double AVERAGE_COURIER_SPEED_METERS_PER_MINUTE = 250.0;

    public record Coordinate(double latitude, double longitude) {}

    public record RouteLegResult(double distanceMeters, double durationSeconds) {}

    public record RouteResult(
            boolean success,
            double totalDistanceMeters,
            double totalDurationSeconds,
            List<RouteLegResult> legs
    ) {}

    public RouteResult calculateRoute(String transportType, List<Coordinate> coordinates) {
        if (coordinates == null || coordinates.size() < 2) {
            return fallbackResult(coordinates);
        }

        String profile = mapTransportTypeToProfile(transportType);
        String coordsCsv = coordinates.stream()
                .map(c -> c.longitude() + "," + c.latitude())
                .collect(Collectors.joining(";"));

        try {
            String payload = RestClient.builder()
                    .baseUrl(osrmBaseUrl)
                    .build()
                    .get()
                    .uri("/route/v1/{profile}/{coords}?overview=false&steps=false", profile, coordsCsv)
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(payload);
            String code = root.path("code").asText();
            if (!"Ok".equalsIgnoreCase(code)) {
                log.warn("OSRM returned non-OK code: {}, falling back to Haversine", code);
                return fallbackResult(coordinates);
            }

            JsonNode routeNode = root.path("routes").path(0);
            if (routeNode.isMissingNode() || routeNode.isNull()) {
                log.warn("OSRM response has no routes, falling back to Haversine");
                return fallbackResult(coordinates);
            }

            double totalDistance = routeNode.path("distance").asDouble();
            double totalDuration = routeNode.path("duration").asDouble();

            List<RouteLegResult> legs = new ArrayList<>();
            JsonNode legsNode = routeNode.path("legs");
            if (legsNode.isArray()) {
                for (JsonNode legNode : legsNode) {
                    legs.add(new RouteLegResult(
                            legNode.path("distance").asDouble(),
                            legNode.path("duration").asDouble()
                    ));
                }
            }

            if (legs.isEmpty()) {
                return fallbackResult(coordinates);
            }

            log.info("OSRM routing successful: profile={}, segments={}, distance={}m, duration={}s",
                    profile, legs.size(), totalDistance, totalDuration);

            return new RouteResult(true, totalDistance, totalDuration, legs);

        } catch (RestClientException ex) {
            log.warn("OSRM API client exception: {}, falling back to Haversine", ex.getMessage());
            return fallbackResult(coordinates);
        } catch (Exception ex) {
            log.warn("OSRM API unexpected exception: {}, falling back to Haversine", ex.getMessage());
            return fallbackResult(coordinates);
        }
    }

    private String mapTransportTypeToProfile(String transportType) {
        if (transportType == null) {
            return "driving";
        }
        return switch (transportType.trim().toUpperCase()) {
            case "FOOT" -> "foot";
            case "BIKE", "SCOOTER" -> "bicycle";
            case "CAR", "VAN" -> "driving";
            default -> "driving";
        };
    }

    private RouteResult fallbackResult(List<Coordinate> coordinates) {
        if (coordinates == null || coordinates.size() < 2) {
            return new RouteResult(false, 0.0, 0.0, List.of());
        }

        double totalDistance = 0.0;
        double totalDuration = 0.0;
        List<RouteLegResult> legs = new ArrayList<>();

        Coordinate cursor = coordinates.get(0);
        for (int i = 1; i < coordinates.size(); i++) {
            Coordinate next = coordinates.get(i);
            double dist = haversineMeters(cursor, next);
            double dur = dist / (AVERAGE_COURIER_SPEED_METERS_PER_MINUTE / 60.0);
            legs.add(new RouteLegResult(dist, dur));
            totalDistance += dist;
            totalDuration += dur;
            cursor = next;
        }

        return new RouteResult(false, totalDistance, totalDuration, legs);
    }

    private double haversineMeters(Coordinate a, Coordinate b) {
        double earthRadius = 6_371_000.0;
        double dLat = Math.toRadians(b.latitude() - a.latitude());
        double dLon = Math.toRadians(b.longitude() - a.longitude());
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());
        double sinLat = Math.sin(dLat / 2);
        double sinLon = Math.sin(dLon / 2);
        double h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        return earthRadius * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }
}
