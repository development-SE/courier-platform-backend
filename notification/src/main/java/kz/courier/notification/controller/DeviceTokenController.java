package kz.courier.notification.controller;

import jakarta.validation.Valid;
import kz.courier.notification.dto.DeviceTokenDto;
import kz.courier.notification.service.DeviceTokenService;
import kz.courier.notification.service.GatewayPrincipalProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/devices")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;
    private final GatewayPrincipalProvider principalProvider;

    @PostMapping
    public ResponseEntity<DeviceTokenDto.ApiResponse<Void>> register(
            @RequestHeader("X-User-Id") String userIdHeader,
            @Valid @RequestBody DeviceTokenDto.RegisterDeviceRequest request) {

        UUID userId = principalProvider.requireUserId(userIdHeader);
        deviceTokenService.register(userId, request);
        return ResponseEntity.ok(DeviceTokenDto.ApiResponse.ok("Device token registered", null));
    }

    @DeleteMapping("/{deviceId}")
    public ResponseEntity<DeviceTokenDto.ApiResponse<Void>> revoke(
            @RequestHeader("X-User-Id") String userIdHeader,
            @PathVariable String deviceId) {

        UUID userId = principalProvider.requireUserId(userIdHeader);
        deviceTokenService.revoke(userId, deviceId);
        return ResponseEntity.ok(DeviceTokenDto.ApiResponse.ok("Device token revoked", null));
    }

    @PatchMapping("/{deviceId}/enabled")
    public ResponseEntity<DeviceTokenDto.ApiResponse<Void>> updateEnabled(
            @RequestHeader("X-User-Id") String userIdHeader,
            @PathVariable String deviceId,
            @Valid @RequestBody DeviceTokenDto.UpdateDeviceEnabledRequest request) {

        UUID userId = principalProvider.requireUserId(userIdHeader);
        deviceTokenService.updateEnabled(userId, deviceId, request.enabled());
        return ResponseEntity.ok(DeviceTokenDto.ApiResponse.ok("Device token preference updated", null));
    }
}
