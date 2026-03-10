package kz.courier.authservice.service;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.jsonwebtoken.Claims;
import kz.courier.authservice.dto.NotificationEvent;
import kz.courier.authservice.model.ConfirmationToken;
import kz.courier.authservice.model.LoginLog;
import kz.courier.authservice.model.Role;
import kz.courier.authservice.model.TokenType;
import kz.courier.authservice.model.User;
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
            validateEmail(req.getEmail());
            if (userRepo.existsByEmail(req.getEmail())) {
                fail(resp, "EMAIL_EXISTS", "E-mail already taken");
                return;
            }
            if (userRepo.existsByPhone(req.getPhone())) {
                fail(resp, "PHONE_EXISTS", "Phone already taken");
                return;
            }
            validatePassword(req.getPassword());
            validateNames(req.getFirstName(), req.getLastName());

            User user = User.builder()
                    .email(req.getEmail())
                    .passwordHash(passwordEncoder.encode(req.getPassword()))
                    .phone(req.hasPhone() ? req.getPhone() : null)
                    .firstName(req.getFirstName())
                    .lastName(req.getLastName())
                    .pushConsent(req.getPushConsent())
                    .role(Role.valueOf(req.getRole().name()))
                    .active(true)  
                    .build();
            user = userRepo.save(user);

            String token = UUID.randomUUID().toString();
            ConfirmationToken ct = ConfirmationToken.builder()
                    .user(user)
                    .token(token)
                    .type(TokenType.EMAIL_VERIFY)
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build();
            tokenRepo.save(ct);

            notificationProducer.publish(NotificationEvent.builder()
                    .userId(user.getId().toString())
                    .type("email_verification")
                    .payload(Map.of(
                            "user_name",   user.getFirstName(),
                            "email",       user.getEmail(),
                            "verify_link", "http://localhost:8081/auth/verify?token=" + token
                    ))
                    .build());

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
            boolean valid = user.isActive() &&
                    passwordEncoder.matches(req.getPassword(), user.getPasswordHash());
            logLogin(user, req.getDeviceId(), valid);

            if (!valid) {
                fail(resp, "INVALID_CREDENTIALS", "Wrong e-mail or password");
                return;
            }

            String access = jwtService.generateAccessToken(
                user.getId(), 
                user.getRole().name(),
                user.getCompanyId()  // ← need to add this field to User entity
            );
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

            String access = jwtService.generateAccessToken(
                user.getId(), 
                user.getRole().name(),
                user.getCompanyId()  // ← need to add this field to User entity
            );
            String newRefresh = jwtService.generateRefreshToken(userId);

            RefreshTokenResponse reply = RefreshTokenResponse.newBuilder()
                    .setResponse(successResponse())
                    .setAccessToken(access)
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

    /* ====================== GET USER ====================== */
    @Override
    public void getUser(GetUserRequest req, StreamObserver<GetUserResponse> resp) {
        try {
            UUID userId = UUID.fromString(req.getUserId());
            User user = userRepo.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));

            GetUserResponse reply = GetUserResponse.newBuilder()
                    .setUserId(user.getId().toString())
                    .setEmail(user.getEmail())
                    .setFirstName(user.getFirstName())
                    .setLastName(user.getLastName())
                    .setPhone(user.getPhone() != null ? user.getPhone() : "")
                    .setRole(kz.courier.auth.v1.Role.valueOf(user.getRole().name()))
                    .setIsEmailVerified(user.isEmailVerified())
                    .setCreatedAt(Timestamp.newBuilder()
                            .setSeconds(user.getCreatedAt().toEpochSecond(ZoneOffset.UTC))
                            .build())
                    .build();
            resp.onNext(reply);
            resp.onCompleted();

        } catch (Exception e) {
            resp.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    /* ====================== UPDATE USER ====================== */
    @Override
    public void updateUser(UpdateUserRequest req, StreamObserver<kz.courier.common.v1.Response> resp) {
        try {
            UUID userId = UUID.fromString(req.getUserId());
            User user = userRepo.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));

            if (req.hasEmail()) {
                validateEmail(req.getEmail());
                if (userRepo.existsByEmail(req.getEmail())) {
                    fail(resp, "EMAIL_EXISTS", "E-mail already taken");
                    return;
                }
                user.setEmail(req.getEmail());
            }
            if (req.hasFirstName())   user.setFirstName(req.getFirstName());
            if (req.hasLastName())    user.setLastName(req.getLastName());
            if (req.hasPhone())       user.setPhone(req.getPhone());
            if (req.hasPushConsent()) user.setPushConsent(req.getPushConsent());

            user.setUpdatedAt(LocalDateTime.now());
            userRepo.save(user);

            resp.onNext(successResponse());
            resp.onCompleted();

        } catch (Exception e) {
            resp.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    /* ====================== CHANGE PASSWORD ====================== */
    @Override
    public void changePassword(ChangePasswordRequest req, StreamObserver<kz.courier.common.v1.Response> resp) {
        try {
            UUID userId = UUID.fromString(req.getUserId());
            User user = userRepo.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));

            if (!passwordEncoder.matches(req.getOldPassword(), user.getPasswordHash())) {
                fail(resp, "INVALID_PASSWORD", "Current password is incorrect");
                return;
            }

            validatePassword(req.getNewPassword());
            user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
            user.setUpdatedAt(LocalDateTime.now());
            userRepo.save(user);

            notificationProducer.publish(NotificationEvent.builder()
                    .userId(user.getId().toString())
                    .type("password_changed")
                    .payload(Map.of(
                            "user_name",  user.getFirstName(),
                            "email",      user.getEmail(),
                            "changed_at", Instant.now().toString()
                    ))
                    .build());

            resp.onNext(successResponse());
            resp.onCompleted();

        } catch (Exception e) {
            resp.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    /* ====================== DELETE USER ====================== */
    @Override
    public void deleteUser(DeleteUserRequest req, StreamObserver<kz.courier.common.v1.Response> resp) {
        try {
            UUID userId = UUID.fromString(req.getUserId());
            User user = userRepo.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));

            String firstName = user.getFirstName();
            String email     = user.getEmail();
            String deletedAt = Instant.now().toString();

            tokenRepo.deleteAllByUser(user);
            logRepo.deleteAllByUser(user);
            userRepo.delete(user);

            notificationProducer.publish(NotificationEvent.builder()
                    .userId(userId.toString())
                    .type("account_deleted")
                    .payload(Map.of(
                            "user_name",  firstName,
                            "email",      email,
                            "deleted_at", deletedAt
                    ))
                    .build());

            resp.onNext(successResponse());
            resp.onCompleted();

        } catch (Exception e) {
            resp.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    /* ====================== LIST USERS (admin) ====================== */
    @Override
    public void listUsers(ListUsersRequest req, StreamObserver<ListUsersResponse> resp) {
        var page      = req.getPagination();
        var pageable  = org.springframework.data.domain.PageRequest.of(
                page.getPage() - 1, page.getPageSize());
        var usersPage = req.hasFilterRole()
                ? userRepo.findByRole(Role.valueOf(req.getFilterRole()), pageable)
                : userRepo.findAll(pageable);

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
        obs.onError(Status.INVALID_ARGUMENT
                .withDescription(code + ": " + msg)
                .asRuntimeException());
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