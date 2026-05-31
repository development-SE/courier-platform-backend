package kz.courier.courierservice.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "courier_profiles")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CourierProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "company_id")
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "courier_type", nullable = false, length = 30)
    private CourierType courierType;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_status", nullable = false, length = 30)
    private EmploymentStatus employmentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport_type", length = 30)
    private TransportType transportType;

    @Column(name = "is_verified", nullable = false)
    private boolean verified;

    @Column(name = "can_take_orders", nullable = false)
    private boolean canTakeOrders;

    @Column(name = "max_active_orders", nullable = false)
    private int maxActiveOrders;

    @Column(name = "notes")
    private String notes;

    @Builder.Default
    @OneToMany(mappedBy = "courier", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CourierWorkSchedule> schedules = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
