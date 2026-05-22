package kz.courier.notification.controller;

import jakarta.validation.Valid;
import kz.courier.notification.dto.DeviceTokenDto;
import kz.courier.notification.model.DeviceToken;
import kz.courier.notification.repository.DeviceTokenRepository;
import kz.courier.notification.service.GatewayPrincipalProvider;
import kz.courier.notification.service.PushMessage;
import kz.courier.notification.service.PushNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/push")
@RequiredArgsConstructor
public class PushTestController {

    private final GatewayPrincipalProvider principalProvider;
    private final DeviceTokenRepository deviceTokenRepository;
    private final PushNotificationService pushNotificationService;

    @PostMapping("/test")
    public ResponseEntity<DeviceTokenDto.ApiResponse<Void>> sendTestPush(
            @RequestHeader("X-User-Id") String userIdHeader,
            @RequestHeader("X-User-Roles") String rolesHeader,
            @Valid @RequestBody DeviceTokenDto.TestPushRequest request) {

        if (!principalProvider.hasAnyRole(rolesHeader, "ADMIN", "SUPER_ADMIN")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(DeviceTokenDto.ApiResponse.ok("Admin role required", null));
        }

        UUID userId = principalProvider.requireUserId(userIdHeader);
        PushMessage message = new PushMessage(
                request.title(),
                request.body(),
                Map.of("type", "test_push"));

        for (DeviceToken token : deviceTokenRepository.findAllByUserIdAndEnabledTrueAndRevokedAtIsNull(userId)) {
            pushNotificationService.send(token, message);
        }

        return ResponseEntity.ok(DeviceTokenDto.ApiResponse.ok("Test push processed", null));
    }
}
