// package kz.courier.notification.service;

// import kz.courier.notification.dto.NotificationEvent;
// import org.junit.jupiter.api.BeforeEach;
// import org.junit.jupiter.api.Test;
// import org.junit.jupiter.api.extension.ExtendWith;
// import org.mockito.*;
// import org.mockito.junit.jupiter.MockitoExtension;

// import java.time.Instant;
// import java.util.Map;
// import java.util.UUID;

// import static org.mockito.ArgumentMatchers.*;
// import static org.mockito.Mockito.*;

// @ExtendWith(MockitoExtension.class)
// class NotificationDispatcherTest {

//     @Mock
//     private EmailService emailService;

//     @InjectMocks
//     private NotificationDispatcher dispatcher;

//     private NotificationEvent baseEvent;

//     @BeforeEach
//     void setUp() {
//         baseEvent = NotificationEvent.builder()
//                 .eventId(UUID.randomUUID().toString())
//                 .userId("user-123")
//                 .createdAt(Instant.now())
//                 .build();
//     }

//     @Test
//     void shouldSendEmailVerification() {
//         baseEvent.setType("email_verification");
//         baseEvent.setPayload(Map.of(
//                 "user_name", "Alice",
//                 "email", "alice@example.com",
//                 "verify_link", "http://localhost:8081/auth/verify?token=abc"
//         ));

//         dispatcher.dispatch(baseEvent);

//         verify(emailService).sendHtml(
//                 eq("alice@example.com"),
//                 contains("Confirm"),
//                 eq("email-verification"),
//                 anyMap()
//         );
//     }

//     @Test
//     void shouldSendPasswordChangedNotification() {
//         baseEvent.setType("password_changed");
//         baseEvent.setPayload(Map.of(
//                 "user_name", "Bob",
//                 "email", "bob@example.com",
//                 "changed_at", "2024-01-01T12:00:00Z"
//         ));

//         dispatcher.dispatch(baseEvent);

//         verify(emailService).sendHtml(
//                 eq("bob@example.com"),
//                 contains("password"),
//                 eq("password-changed"),
//                 anyMap()
//         );
//     }

//     @Test
//     void shouldSendAccountDeletedNotification() {
//         baseEvent.setType("account_deleted");
//         baseEvent.setPayload(Map.of(
//                 "user_name", "Carol",
//                 "email", "carol@example.com",
//                 "deleted_at", "2024-06-15T09:30:00Z"
//         ));

//         dispatcher.dispatch(baseEvent);

//         verify(emailService).sendHtml(
//                 eq("carol@example.com"),
//                 contains("deleted"),
//                 eq("account-deleted"),
//                 anyMap()
//         );
//     }

//     @Test
//     void shouldSkipUnknownEventType() {
//         baseEvent.setType("unknown_event");
//         baseEvent.setPayload(Map.of());

//         dispatcher.dispatch(baseEvent);

//         verifyNoInteractions(emailService);
//     }

//     @Test
//     void shouldSkipNullEventType() {
//         baseEvent.setType(null);

//         dispatcher.dispatch(baseEvent);

//         verifyNoInteractions(emailService);
//     }
// }