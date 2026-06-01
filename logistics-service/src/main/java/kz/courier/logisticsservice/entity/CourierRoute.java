package kz.courier.logisticsservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "courier_routes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourierRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "courier_id", nullable = false)
    private UUID courierId;

    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private RouteStatus status;

    @Column(name = "current_load_units", nullable = false)
    private Integer currentLoadUnits;

    @Column(name = "max_capacity_units", nullable = false)
    private Integer maxCapacityUnits;

    @Column(name = "active_orders_count", nullable = false)
    private Integer activeOrdersCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
