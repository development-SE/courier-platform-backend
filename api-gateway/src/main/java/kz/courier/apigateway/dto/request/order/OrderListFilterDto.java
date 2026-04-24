package kz.courier.apigateway.dto.request.order;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class OrderListFilterDto {
    private String companyId;
    private String userId;
    private String status;
    private OffsetDateTime fromDate;
    private OffsetDateTime toDate;
    private Double minAmount;
    private Double maxAmount;
    private int page;
    private int size;
    private String sortBy;
    private boolean sortDesc;
}
