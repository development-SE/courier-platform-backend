package kz.courier.userservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class UserAddressDto {

    @Data
    public static class CreateRequest {
        @Size(max = 100, message = "Label must be at most 100 characters")
        private String label;

        @NotBlank(message = "City is required")
        @Size(max = 100, message = "City must be at most 100 characters")
        private String city;

        @NotBlank(message = "Street is required")
        @Size(max = 255, message = "Street must be at most 255 characters")
        private String street;

        @NotBlank(message = "House is required")
        @Size(max = 50, message = "House must be at most 50 characters")
        private String house;

        @Size(max = 50, message = "Apartment must be at most 50 characters")
        private String apartment;

        @Size(max = 50, message = "Entrance must be at most 50 characters")
        private String entrance;

        @Size(max = 50, message = "Floor must be at most 50 characters")
        private String floor;

        @NotNull(message = "Latitude is required")
        @DecimalMin(value = "-90.0", message = "Latitude must be >= -90")
        @DecimalMax(value = "90.0", message = "Latitude must be <= 90")
        private Double latitude;

        @NotNull(message = "Longitude is required")
        @DecimalMin(value = "-180.0", message = "Longitude must be >= -180")
        @DecimalMax(value = "180.0", message = "Longitude must be <= 180")
        private Double longitude;

        private Boolean defaultAddress;
    }

    @Data
    public static class UpdateRequest {
        @Size(max = 100, message = "Label must be at most 100 characters")
        private String label;

        @Size(max = 100, message = "City must be at most 100 characters")
        private String city;

        @Size(max = 255, message = "Street must be at most 255 characters")
        private String street;

        @Size(max = 50, message = "House must be at most 50 characters")
        private String house;

        @Size(max = 50, message = "Apartment must be at most 50 characters")
        private String apartment;

        @Size(max = 50, message = "Entrance must be at most 50 characters")
        private String entrance;

        @Size(max = 50, message = "Floor must be at most 50 characters")
        private String floor;

        @DecimalMin(value = "-90.0", message = "Latitude must be >= -90")
        @DecimalMax(value = "90.0", message = "Latitude must be <= 90")
        private Double latitude;

        @DecimalMin(value = "-180.0", message = "Longitude must be >= -180")
        @DecimalMax(value = "180.0", message = "Longitude must be <= 180")
        private Double longitude;

        private Boolean defaultAddress;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Response {
        private UUID id;
        private UUID userId;
        private String label;
        private String city;
        private String street;
        private String house;
        private String apartment;
        private String entrance;
        private String floor;
        private Double latitude;
        private Double longitude;
        private boolean defaultAddress;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    public static class PageResponse {
        private List<Response> content;
        private int page;
        private int pageSize;
        private long totalItems;
        private int totalPages;
    }
}
