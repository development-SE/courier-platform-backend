package kz.courier.courierservice.controller;

import jakarta.validation.Valid;
import kz.courier.courierservice.dto.CourierDto;
import kz.courier.courierservice.service.CourierService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.domain.Page;
import kz.courier.courierservice.security.GatewayPrincipalProvider;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/couriers")
@RequiredArgsConstructor
public class CourierController {

    private final CourierService courierService;
    private final GatewayPrincipalProvider gatewayPrincipalProvider;

    @PostMapping
    public ResponseEntity<CourierDto.ApiResponse<CourierDto.CourierProfileResponse>> create(
            @Valid @RequestBody CourierDto.CreateCourierRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CourierDto.ApiResponse.ok(courierService.create(req)));
    }

    @GetMapping("/me")
    public ResponseEntity<CourierDto.ApiResponse<CourierDto.CourierProfileResponse>> getMe() {
        UUID userId = gatewayPrincipalProvider.requireCurrentUserId();
        return ResponseEntity.ok(CourierDto.ApiResponse.ok(courierService.getByUserId(userId)));
    }

    @GetMapping
    public ResponseEntity<CourierDto.ApiResponse<Page<CourierDto.CourierProfileResponse>>> list(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(CourierDto.ApiResponse.ok(
                courierService.list(companyId, page, size)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CourierDto.ApiResponse<CourierDto.CourierProfileResponse>> get(
            @PathVariable UUID id) {
        return ResponseEntity.ok(CourierDto.ApiResponse.ok(courierService.get(id)));
    }

    @GetMapping("/by-user/{userId}")
    public ResponseEntity<CourierDto.ApiResponse<CourierDto.CourierProfileResponse>> getByUserId(
            @PathVariable UUID userId) {
        return ResponseEntity.ok(CourierDto.ApiResponse.ok(courierService.getByUserId(userId)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CourierDto.ApiResponse<CourierDto.CourierProfileResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody CourierDto.UpdateCourierRequest req) {
        return ResponseEntity.ok(CourierDto.ApiResponse.ok(courierService.update(id, req)));
    }

    @GetMapping("/{id}/eligibility")
    public ResponseEntity<CourierDto.ApiResponse<CourierDto.EligibilityResponse>> getEligibility(
            @PathVariable UUID id,
            @RequestParam(required = false) OffsetDateTime at) {
        return ResponseEntity.ok(CourierDto.ApiResponse.ok(courierService.getEligibility(id, at)));
    }
}