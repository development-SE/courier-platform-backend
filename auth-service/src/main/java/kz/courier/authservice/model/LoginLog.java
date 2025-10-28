package kz.courier.authservice.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity @Table(name = "login_logs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LoginLog {
    @Id @GeneratedValue
    private UUID id;

    @ManyToOne @JoinColumn(name = "user_id")
    private User user;

    private String ipAddress;
    private String userAgent;
    private boolean success;
    private LocalDateTime timestamp = LocalDateTime.now();
}
