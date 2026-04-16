package kz.courier.companyservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class CompanyDto {

    @Data
    public static class CreateRequest {
        @NotBlank(message = "Name is required")
        @Size(min = 2, max = 255)
        private String name;

        @NotBlank(message = "BIN is required")
        @Size(min = 12, max = 12, message = "BIN must be exactly 12 characters")
        private String bin;
    }

    @Data
    public static class UpdateRequest {
        @Size(min = 2, max = 255)
        private String name;

        @Size(min = 12, max = 12, message = "BIN must be exactly 12 characters")
        private String bin;
    }

    @Data @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Response {
        private UUID id;
        private String name;
        private String bin;
        private UUID directorId;    // employee.id of the DIRECTOR, null if none
        private String director;    // "FirstName LastName" of the DIRECTOR, null if none
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