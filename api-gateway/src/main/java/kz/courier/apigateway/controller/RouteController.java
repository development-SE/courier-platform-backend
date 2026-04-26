package kz.courier.apigateway.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import kz.courier.apigateway.dto.request.route.RouteCalculationRequest;
import kz.courier.apigateway.dto.response.RouteCalculationResponse;
import kz.courier.apigateway.service.RouteService;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Validated
@RestController
@RequestMapping("/api/routes")
@RequiredArgsConstructor
public class RouteController {

    private final RouteService routeService;

    @PostMapping("/calculate")
    public Mono<ResponseEntity<RouteCalculationResponse>> calculateRoute(
            @Valid @RequestBody RouteCalculationRequest request
    ) {
        return routeService.calculate(request).map(ResponseEntity::ok);
    }
}
