package kz.courier.orderservice.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One-time delivery confirmation code generated when a courier arrives.
 *
 * <p>Verification is always performed against a salted hash, TTL and attempts
 * counter.  A short-lived {@code displayCode} is also stored so the customer app
 * can show the code if push delivery fails. In a stricter production deployment
 * that display value should move to Redis or be encrypted with KMS.
 */
@Entity
@Table(name = "delivery_confirmation_codes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryConfirmationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "courier_id", nullable = false)
    private UUID courierId;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(nullable = false)
    private String salt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    @Builder.Default
    private int maxAttempts = 5;

    @Column(nullable = false)
    @Builder.Default
    private boolean consumed = false;

    /**
     * Short-lived plaintext copy used only for customer fallback display.
     *
     * <p>In a stricter production deployment this should be moved to Redis with
     * the same TTL or encrypted with KMS. For this diploma stage it remains
     * bounded by TTL and order-owner authorization.
     */
    @Column(name = "display_code", nullable = false)
    private String displayCode;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public int attemptsRemaining() {
        return Math.max(0, maxAttempts - attempts);
    }
}
