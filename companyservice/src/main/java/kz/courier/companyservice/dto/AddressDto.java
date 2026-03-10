package kz.courier.companyservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class AddressDto {

    @Data
    public static class CreateRequest {
        @NotNull(message = "Company ID is required")
        private UUID companyId;

        @NotBlank(message = "Street is required")
        private String street;

        @NotBlank(message = "House is required")
        private String house;

        private String apartment;
        private String entrance;
    }

    @Data
    public static class UpdateRequest {
        private String street;
        private String house;
        private String apartment;
        private String entrance;
    }

    @Data @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Response {
        private UUID id;
        private UUID companyId;
        private String street;
        private String house;
        private String apartment;
        private String entrance;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data @Builder
    public static class PageResponse {
        private List<Response> content;
        private int page;
        private int pageSize;
        private long totalItems;
        private int totalPages;
    }
}