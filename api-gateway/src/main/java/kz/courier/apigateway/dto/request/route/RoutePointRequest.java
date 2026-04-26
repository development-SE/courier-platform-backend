package kz.courier.apigateway.dto.request.route;

import jakarta.validation.constraints.NotNull;

public record RoutePointRequest(
        @NotNull Double lat,
        @NotNull Double lng
) {
}
