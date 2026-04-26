package kz.courier.apigateway.dto.response;

public record RouteCalculationResponse(
        long distanceMeters,
        long durationSeconds,
        String encodedPolyline
) {
}
