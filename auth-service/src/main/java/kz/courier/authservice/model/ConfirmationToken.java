package kz.courier.authservice.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity @Table(name = "confirmation_tokens")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ConfirmationToken {
    @Id @GeneratedValue
    private UUID id;

    @ManyToOne @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, unique = true)
    private String token;

    @Enumerated(EnumType.STRING)
    private TokenType type;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    private boolean used = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}

