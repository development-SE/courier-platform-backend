package kz.courier.apigateway.dto.request.route;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record RouteCalculationRequest(
        @Valid @NotNull RoutePointRequest origin,
        @Valid @NotNull RoutePointRequest destination
) {
}
