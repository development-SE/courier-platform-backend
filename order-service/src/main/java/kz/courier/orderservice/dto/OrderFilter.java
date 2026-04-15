package kz.courier.orderservice.dto;

import kz.courier.orderservice.model.OrderStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable application-level filter for order search.
 *
 * <p>The gRPC layer converts proto primitives into this typed object once, then
 * repositories/specifications can reuse it from any entry point: gRPC, Kafka
 * consumers, scheduled jobs, or future REST controllers.
 */
public record OrderFilter(
        UUID userId,
        UUID companyId,
        OrderStatus status,
        OffsetDateTime createdAfter,
        OffsetDateTime createdBefore,
        BigDecimal minAmount,
        BigDecimal maxAmount
) {
    public OrderFilter withUserId(UUID userId) {
        return new OrderFilter(userId, companyId, status, createdAfter, createdBefore,
                minAmount, maxAmount);
    }

    public OrderFilter withCompanyId(UUID companyId) {
        return new OrderFilter(userId, companyId, status, createdAfter, createdBefore,
                minAmount, maxAmount);
    }
}
