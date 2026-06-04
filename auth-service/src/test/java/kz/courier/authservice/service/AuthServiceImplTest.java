package kz.courier.authservice.service;

import kz.courier.auth.v1.LoginRequest;
import kz.courier.auth.v1.LoginResponse;
import kz.courier.auth.v1.LogoutAllRequest;
import kz.courier.auth.v1.LogoutRequest;
import kz.courier.auth.v1.RefreshTokenRequest;
import kz.courier.auth.v1.RefreshTokenResponse;
import kz.courier.authservice.model.RefreshToken;
import kz.courier.authservice.model.Role;
import kz.courier.authservice.model.User;
import kz.courier.authservice.repository.ConfirmationTokenRepository;
import kz.courier.authservice.repository.LoginLogRepository;
import kz.courier.authservice.repository.RefreshTokenRepository;
import kz.courier.authservice.repository.UserRepository;
import kz.courier.authservice.support.RecordingObserver;
import kz.courier.authservice.support.TestUsers;
import kz.courier.common.v1.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private ConfirmationTokenRepository confirmationTokenRepository;
    @Mock
    private LoginLogRepository loginLogRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private AuthServiceImpl service;
    private JwtService jwtService;
    private RefreshTokenHasher refreshTokenHasher;
    private NotificationProducer notificationProducer;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(
                "0123456789012345678901234567890123456789012345678901234567890123",
                15,
                60
        );
        refreshTokenHasher = new RefreshTokenHasher("test-pepper");
        notificationProducer = new NotificationProducer(null);
        service = new AuthServiceImpl(
                userRepository,
                confirmationTokenRepository,
                loginLogRepository,
                refreshTokenRepository,
                passwordEncoder,
                jwtService,
                refreshTokenHasher,
                notificationProducer
        );
        ReflectionTestUtils.setField(service, "accessExpiryMin", 15L);
        ReflectionTestUtils.setField(service, "apiBaseUrl", "http://localhost:8080");
        ReflectionTestUtils.setField(service, "apiVerifyPath", "/verify");
    }

    @Test
    void should_StoreHashedRefreshToken_When_LoginSucceeds() {
        User user = TestUsers.activeVerifiedUser();

        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("StrongPass1!", user.getPasswordHash())).thenReturn(true);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RecordingObserver<LoginResponse> observer = new RecordingObserver<>();
        service.login(LoginRequest.newBuilder()
                .setEmail(user.getEmail())
                .setPassword("StrongPass1!")
                .setDeviceId("127.0.0.1")
                .build(), observer);

        ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(tokenCaptor.capture());

        RefreshToken savedToken = tokenCaptor.getValue();
        assertThat(observer.completed()).isTrue();
        assertThat(observer.error()).isNull();
        assertThat(observer.value().getResponse().getSuccess()).isTrue();
        assertThat(observer.value().getAccessToken()).isNotBlank();
        assertThat(observer.value().getRefreshToken()).isNotBlank();
        assertThat(savedToken.getTokenHash()).isEqualTo(refreshTokenHasher.hash(observer.value().getRefreshToken()));
        assertThat(savedToken.getTokenHash()).isNotEqualTo(observer.value().getRefreshToken());
        assertThat(savedToken.getUser().getId()).isEqualTo(user.getId());
        assertThat(savedToken.getExpiresAt()).isAfter(savedToken.getIssuedAt());
        assertThat(savedToken.getTokenFamilyId()).isNotNull();
        assertThat(savedToken.getRevokedAt()).isNull();
    }

    @Test
    void should_RotateRefreshToken_When_RefreshTokenIsValid() {
        User user = TestUsers.activeVerifiedUser();
        UUID familyId = UUID.randomUUID();
        JwtService.RefreshTokenDetails oldRefreshDetails = jwtService.generateRefreshToken(user.getId());

        RefreshToken currentToken = RefreshToken.builder()
                .id(oldRefreshDetails.tokenId())
                .user(user)
                .tokenHash(refreshTokenHasher.hash(oldRefreshDetails.token()))
                .issuedAt(LocalDateTime.now().minusMinutes(5))
                .expiresAt(LocalDateTime.now().plusMinutes(55))
                .tokenFamilyId(familyId)
                .createdAt(LocalDateTime.now().minusMinutes(5))
                .updatedAt(LocalDateTime.now().minusMinutes(5))
                .build();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(refreshTokenRepository.findByTokenHash(currentToken.getTokenHash())).thenReturn(Optional.of(currentToken));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RecordingObserver<RefreshTokenResponse> observer = new RecordingObserver<>();
        service.refreshToken(RefreshTokenRequest.newBuilder()
                .setRefreshToken(oldRefreshDetails.token())
                .build(), observer);

        assertThat(observer.completed()).isTrue();
        assertThat(observer.value().getResponse().getSuccess()).isTrue();
        assertThat(observer.value().getAccessToken()).isNotBlank();
        assertThat(observer.value().getRefreshToken()).isNotBlank();
        assertThat(observer.value().getRefreshToken()).isNotEqualTo(oldRefreshDetails.token());
        assertThat(currentToken.getRevokedAt()).isNotNull();
        assertThat(currentToken.getReplacedByTokenId()).isNotNull();

        ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, times(2)).save(tokenCaptor.capture());
        List<RefreshToken> savedTokens = tokenCaptor.getAllValues();
        RefreshToken replacement = savedTokens.stream()
                .filter(token -> !currentToken.getId().equals(token.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(replacement.getTokenHash()).isEqualTo(refreshTokenHasher.hash(observer.value().getRefreshToken()));
        assertThat(replacement.getUser().getId()).isEqualTo(user.getId());
        assertThat(replacement.getTokenFamilyId()).isEqualTo(familyId);
        assertThat(currentToken.getReplacedByTokenId()).isEqualTo(replacement.getId());
    }

    @Test
    void should_RejectOldRefreshToken_When_TokenWasAlreadyRotated() {
        User user = TestUsers.activeVerifiedUser();
        UUID tokenFamilyId = UUID.randomUUID();
        JwtService.RefreshTokenDetails revokedDetails = jwtService.generateRefreshToken(user.getId());
        RefreshToken revokedToken = RefreshToken.builder()
                .id(revokedDetails.tokenId())
                .user(user)
                .tokenHash(refreshTokenHasher.hash(revokedDetails.token()))
                .issuedAt(LocalDateTime.now().minusMinutes(10))
                .expiresAt(LocalDateTime.now().plusMinutes(20))
                .revokedAt(LocalDateTime.now().minusMinutes(1))
                .tokenFamilyId(tokenFamilyId)
                .build();

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(refreshTokenRepository.findByTokenHash(revokedToken.getTokenHash())).thenReturn(Optional.of(revokedToken));
        when(refreshTokenRepository.findByTokenFamilyId(tokenFamilyId)).thenReturn(List.of(revokedToken));

        RecordingObserver<RefreshTokenResponse> observer = new RecordingObserver<>();
        service.refreshToken(RefreshTokenRequest.newBuilder()
                .setRefreshToken(revokedDetails.token())
                .build(), observer);

        assertThat(observer.completed()).isTrue();
        assertThat(observer.value().getResponse().getSuccess()).isFalse();
        assertThat(observer.value().getResponse().getError().getCode()).isEqualTo("INVALID_REFRESH");
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    void should_RevokeTokenFamily_When_RevokedRefreshTokenIsReused() {
        User user = TestUsers.activeVerifiedUser();
        UUID tokenFamilyId = UUID.randomUUID();
        JwtService.RefreshTokenDetails reusedDetails = jwtService.generateRefreshToken(user.getId());
        RefreshToken reusedToken = RefreshToken.builder()
                .id(reusedDetails.tokenId())
                .user(user)
                .tokenHash(refreshTokenHasher.hash(reusedDetails.token()))
                .issuedAt(LocalDateTime.now().minusHours(1))
                .expiresAt(LocalDateTime.now().plusHours(1))
                .revokedAt(LocalDateTime.now().minusMinutes(30))
                .tokenFamilyId(tokenFamilyId)
                .build();
        RefreshToken activeSibling = RefreshToken.builder()
                .id(UUID.randomUUID())
                .user(user)
                .tokenHash("active-hash")
                .issuedAt(LocalDateTime.now().minusMinutes(5))
                .expiresAt(LocalDateTime.now().plusMinutes(55))
                .tokenFamilyId(tokenFamilyId)
                .build();

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(refreshTokenRepository.findByTokenHash(reusedToken.getTokenHash())).thenReturn(Optional.of(reusedToken));
        when(refreshTokenRepository.findByTokenFamilyId(tokenFamilyId)).thenReturn(List.of(reusedToken, activeSibling));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RecordingObserver<RefreshTokenResponse> observer = new RecordingObserver<>();
        service.refreshToken(RefreshTokenRequest.newBuilder()
                .setRefreshToken(reusedDetails.token())
                .build(), observer);

        assertThat(observer.completed()).isTrue();
        assertThat(observer.value().getResponse().getSuccess()).isFalse();
        assertThat(observer.value().getResponse().getError().getCode()).isEqualTo("INVALID_REFRESH");
        assertThat(activeSibling.getRevokedAt()).isNotNull();
        verify(refreshTokenRepository).save(activeSibling);
    }

    @Test
    void should_RevokeCurrentRefreshToken_When_LogoutIsCalled() {
        User user = TestUsers.activeVerifiedUser();
        JwtService.RefreshTokenDetails refreshDetails = jwtService.generateRefreshToken(user.getId());
        RefreshToken token = RefreshToken.builder()
                .id(refreshDetails.tokenId())
                .user(user)
                .tokenHash(refreshTokenHasher.hash(refreshDetails.token()))
                .issuedAt(LocalDateTime.now().minusMinutes(2))
                .expiresAt(LocalDateTime.now().plusHours(1))
                .tokenFamilyId(UUID.randomUUID())
                .build();

        when(refreshTokenRepository.findByTokenHash(token.getTokenHash())).thenReturn(Optional.of(token));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(refreshTokenRepository.findByTokenFamilyId(token.getTokenFamilyId())).thenReturn(List.of(token));

        RecordingObserver<Response> logoutObserver = new RecordingObserver<>();
        service.logout(LogoutRequest.newBuilder()
                .setRefreshToken(refreshDetails.token())
                .build(), logoutObserver);

        assertThat(logoutObserver.completed()).isTrue();
        assertThat(logoutObserver.value().getSuccess()).isTrue();
        assertThat(token.getRevokedAt()).isNotNull();

        RecordingObserver<RefreshTokenResponse> refreshObserver = new RecordingObserver<>();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        service.refreshToken(RefreshTokenRequest.newBuilder()
                .setRefreshToken(refreshDetails.token())
                .build(), refreshObserver);

        assertThat(refreshObserver.completed()).isTrue();
        assertThat(refreshObserver.value().getResponse().getSuccess()).isFalse();
        assertThat(refreshObserver.value().getResponse().getError().getCode()).isEqualTo("INVALID_REFRESH");
    }

    @Test
    void should_RevokeAllOwnRefreshTokens_When_UserCallsLogoutAll() {
        UUID userId = UUID.randomUUID();
        RefreshToken firstToken = activeToken(userId);
        RefreshToken secondToken = activeToken(userId);

        when(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId)).thenReturn(List.of(firstToken, secondToken));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RecordingObserver<Response> observer = new RecordingObserver<>();
        service.logoutAll(LogoutAllRequest.newBuilder()
                .setActorUserId(userId.toString())
                .setActorRole(kz.courier.auth.v1.Role.CLIENT)
                .build(), observer);

        assertThat(observer.completed()).isTrue();
        assertThat(observer.value().getSuccess()).isTrue();
        assertThat(firstToken.getRevokedAt()).isNotNull();
        assertThat(secondToken.getRevokedAt()).isNotNull();
        verify(refreshTokenRepository, times(2)).save(any(RefreshToken.class));
    }

    @Test
    void should_RevokeTargetUserTokens_When_AdminCallsLogoutAllForUser() {
        UUID adminId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();
        RefreshToken targetToken = activeToken(targetUserId);

        when(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(targetUserId)).thenReturn(List.of(targetToken));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RecordingObserver<Response> observer = new RecordingObserver<>();
        service.logoutAll(LogoutAllRequest.newBuilder()
                .setActorUserId(adminId.toString())
                .setTargetUserId(targetUserId.toString())
                .setActorRole(kz.courier.auth.v1.Role.ADMIN)
                .build(), observer);

        assertThat(observer.completed()).isTrue();
        assertThat(observer.value().getSuccess()).isTrue();
        assertThat(targetToken.getRevokedAt()).isNotNull();
        verify(refreshTokenRepository).findByUserIdAndRevokedAtIsNull(targetUserId);
        verify(refreshTokenRepository, never()).findByUserIdAndRevokedAtIsNull(adminId);
    }

    @Test
    void should_ReturnForbidden_When_NonAdminCallsLogoutAllForAnotherUser() {
        UUID actorId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();

        RecordingObserver<Response> observer = new RecordingObserver<>();
        service.logoutAll(LogoutAllRequest.newBuilder()
                .setActorUserId(actorId.toString())
                .setTargetUserId(targetUserId.toString())
                .setActorRole(kz.courier.auth.v1.Role.CLIENT)
                .build(), observer);

        assertThat(observer.completed()).isTrue();
        assertThat(observer.value().getSuccess()).isFalse();
        assertThat(observer.value().getError().getCode()).isEqualTo("FORBIDDEN");
        verify(refreshTokenRepository, never()).findByUserIdAndRevokedAtIsNull(any());
    }

    @Test
    void should_RejectToken_When_TokenTypeIsNotRefresh() {
        String accessToken = jwtService.generateAccessToken(
                UUID.randomUUID(),
                "client@example.com",
                "CLIENT",
                null
        );

        RecordingObserver<RefreshTokenResponse> observer = new RecordingObserver<>();
        service.refreshToken(RefreshTokenRequest.newBuilder()
                .setRefreshToken(accessToken)
                .build(), observer);

        assertThat(observer.completed()).isTrue();
        assertThat(observer.value().getResponse().getSuccess()).isFalse();
        assertThat(observer.value().getResponse().getError().getCode()).isEqualTo("INVALID_REFRESH");
        verify(refreshTokenRepository, never()).findByTokenHash(any());
    }

    @Test
    void should_ReturnUnauthorized_When_RefreshTokenIsMissingOrMalformed() {
        RecordingObserver<RefreshTokenResponse> observer = new RecordingObserver<>();
        service.refreshToken(RefreshTokenRequest.newBuilder()
                .setRefreshToken("malformed-token")
                .build(), observer);

        assertThat(observer.completed()).isTrue();
        assertThat(observer.value().getResponse().getSuccess()).isFalse();
        assertThat(observer.value().getResponse().getError().getCode()).isEqualTo("INVALID_REFRESH");
        assertThat(observer.value().getResponse().getError().getMessage()).doesNotContain("malformed-token");
    }

    private RefreshToken activeToken(UUID userId) {
        return RefreshToken.builder()
                .id(UUID.randomUUID())
                .user(User.builder()
                        .id(userId)
                        .email("user@example.com")
                        .passwordHash("$2a$10$encoded")
                        .firstName("Test")
                        .lastName("User")
                        .role(Role.CLIENT)
                        .active(true)
                        .emailVerified(true)
                        .build())
                .tokenHash("hash-" + UUID.randomUUID())
                .issuedAt(LocalDateTime.now().minusMinutes(5))
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .tokenFamilyId(UUID.randomUUID())
                .createdAt(LocalDateTime.now().minusMinutes(5))
                .updatedAt(LocalDateTime.now().minusMinutes(5))
                .build();
    }
}
