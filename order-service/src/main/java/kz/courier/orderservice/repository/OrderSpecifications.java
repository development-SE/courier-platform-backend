package kz.courier.orderservice.repository;

import kz.courier.orderservice.dto.OrderFilter;
import kz.courier.orderservice.model.Order;
import kz.courier.orderservice.model.OrderStatus;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Composable JPA Specifications for order search.
 *
 * <p>Specification-based filtering keeps list/search logic readable and avoids
 * creating repository methods for every possible filter combination.
 */
public final class OrderSpecifications {

    private OrderSpecifications() {
    }

    public static Specification<Order> byFilter(OrderFilter filter) {
        return combine(Arrays.asList(
                userIdEquals(filter.userId()),
                companyIdEquals(filter.companyId()),
                statusEquals(filter.status()),
                createdAtGreaterThanOrEqual(filter.createdAfter()),
                createdAtLessThanOrEqual(filter.createdBefore()),
                totalAmountGreaterThanOrEqual(filter.minAmount()),
                totalAmountLessThanOrEqual(filter.maxAmount())
        ));
    }

    private static Specification<Order> combine(List<Specification<Order>> specifications) {
        Specification<Order> result = null;
        for (Specification<Order> specification : specifications) {
            if (specification == null) {
                continue;
            }
            result = result == null ? specification : result.and(specification);
        }

        return result != null ? result : (root, query, cb) -> cb.conjunction();
    }

    private static Specification<Order> userIdEquals(UUID userId) {
        if (userId == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("authorId"), userId);
    }

    private static Specification<Order> companyIdEquals(UUID companyId) {
        if (companyId == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("companyId"), companyId);
    }

    private static Specification<Order> statusEquals(OrderStatus status) {
        if (status == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    private static Specification<Order> createdAtGreaterThanOrEqual(OffsetDateTime createdAfter) {
        if (createdAfter == null) {
            return null;
        }
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), createdAfter);
    }

    private static Specification<Order> createdAtLessThanOrEqual(OffsetDateTime createdBefore) {
        if (createdBefore == null) {
            return null;
        }
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), createdBefore);
    }

    private static Specification<Order> totalAmountGreaterThanOrEqual(BigDecimal minAmount) {
        if (minAmount == null) {
            return null;
        }
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("totalAmount"), minAmount);
    }

    private static Specification<Order> totalAmountLessThanOrEqual(BigDecimal maxAmount) {
        if (maxAmount == null) {
            return null;
        }
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("totalAmount"), maxAmount);
    }
}
