package kz.courier.authservice.service;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.jsonwebtoken.Claims;
import kz.courier.authservice.dto.NotificationEvent;
import kz.courier.authservice.model.*;
import kz.courier.authservice.model.Role;
import kz.courier.authservice.repository.*;
import kz.courier.auth.v1.*;
import kz.courier.common.v1.*;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@GrpcService
@RequiredArgsConstructor
@Transactional
public class AuthServiceImpl extends AuthServiceGrpc.AuthServiceImplBase {

    private final UserRepository userRepo;
    private final ConfirmationTokenRepository tokenRepo;
    private final LoginLogRepository logRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final NotificationProducer notificationProducer;

    /* ====================== REGISTER ====================== */
    @Override
    public void register(RegisterRequest req, StreamObserver<RegisterResponse> resp) {
        try {
            // ---- validation -------------------------------------------------
            validateEmail(req.getEmail());
            if (userRepo.existsByEmail(req.getEmail())) {
                fail(resp, "EMAIL_EXISTS", "E-mail already taken");
                return;
            }
            validatePassword(req.getPassword());
            validateNames(req.getFirstName(), req.getLastName());

            // ---- create user ------------------------------------------------
            User user = User.builder()
                    .email(req.getEmail())
                    .passwordHash(passwordEncoder.encode(req.getPassword()))
                    .phone(req.hasPhone() ? req.getPhone() : null)
                    .firstName(req.getFirstName())
                    .lastName(req.getLastName())
                    .pushConsent(req.getPushConsent())
                    .role(Role.valueOf(req.getRole().name()))
                    .build();
            user = userRepo.save(user);

            // ---- confirmation token -----------------------------------------
            String token = UUID.randomUUID().toString();
            ConfirmationToken ct = ConfirmationToken.builder()
                    .user(user)
                    .token(token)
                    .type(TokenType.EMAIL_VERIFY)
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build();
            tokenRepo.save(ct);

            // ---- publish to Kafka -------------------------------------------
            NotificationEvent event = NotificationEvent.builder()
                    .userId(user.getId().toString())
                    .type("email_verification")
                    .payload(Map.of(
                            "user_name", user.getFirstName(),
                            "verify_link", "http://localhost:8081/auth/verify?token=" + token
                    ))
                    .build();
            notificationProducer.publish(event);

            // ---- response ---------------------------------------------------
            RegisterResponse reply = RegisterResponse.newBuilder()
                    .setResponse(successResponse())
                    .setUserId(user.getId().toString())
                    .setConfirmationToken(token)
                    .build();
            resp.onNext(reply);
            resp.onCompleted();
        } catch (Exception e) {
            resp.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    /* ====================== LOGIN ====================== */
    @Override
    public void login(LoginRequest req, StreamObserver<LoginResponse> resp) {
        userRepo.findByEmail(req.getEmail()).ifPresentOrElse(user -> {
            boolean valid = user.isActive() && passwordEncoder.matches(req.getPassword(), user.getPasswordHash());
            logLogin(user, req.getDeviceId(), valid);

            if (!valid) {
                fail(resp, "INVALID_CREDENTIALS", "Wrong e-mail or password");
                return;
            }

            String access = jwtService.generateAccessToken(user.getId(), user.getRole().name());
            String refresh = jwtService.generateRefreshToken(user.getId());

            LoginResponse reply = LoginResponse.newBuilder()
                    .setResponse(successResponse())
                    .setAccessToken(access)
                    .setRefreshToken(refresh)
                    .setExpiresAt(Timestamp.newBuilder()
                            .setSeconds(Instant.now().getEpochSecond() + 15 * 60)
                            .build())
                    .setRole(kz.courier.auth.v1.Role.valueOf(user.getRole().name()))
                    .build();
            resp.onNext(reply);
            resp.onCompleted();
        }, () -> fail(resp, "USER_NOT_FOUND", "User not found"));
    }

    /* ====================== REFRESH TOKEN ====================== */
    @Override
    public void refreshToken(RefreshTokenRequest req, StreamObserver<RefreshTokenResponse> resp) {
        try {
            Claims claims = jwtService.validateAndGetClaims(req.getRefreshToken());
            UUID userId = UUID.fromString(claims.getSubject());
            User user = userRepo.findById(userId).orElseThrow();

            String newAccess = jwtService.generateAccessToken(userId, user.getRole().name());
            String newRefresh = jwtService.generateRefreshToken(userId);

            RefreshTokenResponse reply = RefreshTokenResponse.newBuilder()
                    .setResponse(successResponse())
                    .setAccessToken(newAccess)
                    .setRefreshToken(newRefresh)
                    .setExpiresAt(Timestamp.newBuilder()
                            .setSeconds(Instant.now().getEpochSecond() + 15 * 60)
                            .build())
                    .build();
            resp.onNext(reply);
            resp.onCompleted();
        } catch (Exception ex) {
            fail(resp, "INVALID_REFRESH", "Refresh token invalid or expired");
        }
    }

    /* ====================== VERIFY EMAIL ====================== */
    @Override
    public void verifyEmail(VerifyEmailRequest req, StreamObserver<kz.courier.common.v1.Response> resp) {
        tokenRepo.findByTokenAndUsedFalse(req.getToken()).ifPresentOrElse(t -> {
            if (t.getExpiresAt().isBefore(LocalDateTime.now())) {
                fail(resp, "TOKEN_EXPIRED", "Verification token expired");
                return;
            }
            User u = t.getUser();
            u.setEmailVerified(true);
            userRepo.save(u);
            t.setUsed(true);
            tokenRepo.save(t);

            resp.onNext(successResponse());
            resp.onCompleted();
        }, () -> fail(resp, "TOKEN_INVALID", "Invalid or already used token"));
    }

    /* ====================== LIST USERS (admin) ====================== */
    @Override
    public void listUsers(ListUsersRequest req, StreamObserver<ListUsersResponse> resp) {
        var page = req.getPagination();
        var pageable = org.springframework.data.domain.PageRequest.of(page.getPage() - 1, page.getPageSize());
        var usersPage = userRepo.findAll(pageable);

        var builder = ListUsersResponse.newBuilder()
                .setResponse(successResponse())
                .setPagination(PaginationResponse.newBuilder()
                        .setTotalItems((int) usersPage.getTotalElements())
                        .setCurrentPage(page.getPage())
                        .setPageSize(page.getPageSize())
                        .setTotalPages(usersPage.getTotalPages())
                        .build());

        usersPage.forEach(u -> builder.addUsers(GetUserResponse.newBuilder()
                .setUserId(u.getId().toString())
                .setEmail(u.getEmail())
                .setFirstName(u.getFirstName())
                .setLastName(u.getLastName())
                .setPhone(u.getPhone() != null ? u.getPhone() : "")
                .setRole(kz.courier.auth.v1.Role.valueOf(u.getRole().name()))
                .setIsEmailVerified(u.isEmailVerified())
                .setCreatedAt(Timestamp.newBuilder()
                        .setSeconds(u.getCreatedAt().toEpochSecond(ZoneOffset.UTC))
                        .build())
                .build()));

        resp.onNext(builder.build());
        resp.onCompleted();
    }

    /* ====================== HELPERS ====================== */
    private void fail(StreamObserver<?> obs, String code, String msg) {
        var r = kz.courier.common.v1.Response.newBuilder()
                .setSuccess(false)
                .setError(kz.courier.common.v1.Error.newBuilder().setCode(code).setMessage(msg).build())
                .setTimestamp(nowTs())
                .build();
        if (obs instanceof StreamObserver s) {
            ((StreamObserver<kz.courier.common.v1.Response>) s).onNext(r);
            s.onCompleted();
        }
    }

    private kz.courier.common.v1.Response successResponse() {
        return kz.courier.common.v1.Response.newBuilder()
                .setSuccess(true)
                .setTimestamp(nowTs())
                .build();
    }

    private Timestamp nowTs() {
        Instant i = Instant.now();
        return Timestamp.newBuilder()
                .setSeconds(i.getEpochSecond())
                .setNanos(i.getNano())
                .build();
    }

    private void logLogin(User user, String deviceId, boolean success) {
        LoginLog log = LoginLog.builder()
                .user(user)
                .ipAddress(deviceId != null ? deviceId : "unknown")
                .userAgent("gRPC")
                .success(success)
                .build();
        logRepo.save(log);
    }

    private void validateEmail(String email) {
        if (!email.matches("^[\\w._%+-]+@[\\w.-]+\\.[A-Za-z]{2,}$"))
            throw new IllegalArgumentException("Invalid e-mail format");
    }

    private void validatePassword(String pwd) {
        if (!pwd.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$"))
            throw new IllegalArgumentException("Password must be 8+ chars with upper, lower, digit, special");
    }

    private void validateNames(String first, String last) {
        if (first.isBlank() || last.isBlank() ||
                !first.matches("^[a-zA-Zа-яА-Я\\s-]{2,100}$") ||
                !last.matches("^[a-zA-Zа-яА-Я\\s-]{2,100}$"))
            throw new IllegalArgumentException("Names must be 2-100 letters");
    }
}