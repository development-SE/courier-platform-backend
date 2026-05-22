package kz.courier.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class DeviceTokenDto {

    private DeviceTokenDto() {
    }

    public record RegisterDeviceRequest(
            @NotBlank String deviceId,
            @NotBlank String platform,
            @NotBlank String provider,
            @NotBlank String pushToken,
            String appVersion,
            String locale
    ) {
    }

    public record UpdateDeviceEnabledRequest(
            @NotNull Boolean enabled
    ) {
    }

    public record TestPushRequest(
            @NotBlank String title,
            @NotBlank String body
    ) {
    }

    public record ApiResponse<T>(
            boolean success,
            String message,
            T data
    ) {
        public static <T> ApiResponse<T> ok(String message, T data) {
            return new ApiResponse<>(true, message, data);
        }

        public static <T> ApiResponse<T> error(String message) {
            return new ApiResponse<>(false, message, null);
        }
    }
}
