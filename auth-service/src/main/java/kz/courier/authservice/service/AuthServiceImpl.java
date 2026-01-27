package kz.courier.authservice.service;

import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import io.jsonwebtoken.Claims;
import kz.courier.authservice.dto.NotificationEvent;
import kz.courier.authservice.model.*;
import kz.courier.authservice.model.Role;
import kz.courier.authservice.repository.*;
import kz.courier.auth.v1.*;
import kz.courier.common.v1.*;
import kz.courier.common.v1.Error;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@Slf4j
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
    @Value("${API_BASE_URL}")
    private String apiBaseUrl;
    @Value("${API_VERIFY_PATH}")
    private String apiVerifyPath;

    @Override
    public void register(RegisterRequest req, StreamObserver<RegisterResponse> responseObserver) {
        try {
            // ---- validation -------------------------------------------------
            validateEmail(req.getEmail());

            if (userRepo.existsByEmail(req.getEmail())) {
                sendError(responseObserver, "EMAIL_EXISTS", "E-mail already taken");
                return;
            }

            if (userRepo.existsByPhone(req.getPhone())) {
                sendError(responseObserver, "PHONE_EXISTS", "Phone already taken");
                return;
            }

            validatePassword(req.getPassword());
            validateNames(req.getFirstName(), req.getLastName());

            System.out.println("USER IS creating------");
            System.out.println(req.getPassword());


            // ---- create user ------------------------------------------------
            User user = User.builder()
                    .email(req.getEmail())
                    .passwordHash(passwordEncoder.encode(req.getPassword()))
                    .phone(req.hasPhone() ? req.getPhone() : null)
                    .firstName(req.getFirstName())
                    .lastName(req.getLastName())
                    .pushConsent(req.getPushConsent())
                    .role(Role.valueOf(req.getRole().name()))
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            log.debug("Saving new user: {}", req.getEmail());
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
                            "verify_link", apiBaseUrl + apiVerifyPath + "?token=" + token
                    ))
                    .build();
            notificationProducer.publish(event);

            // ---- success response -------------------------------------------
            RegisterResponse reply = RegisterResponse.newBuilder()
                    .setResponse(successResponse())
                    .setUserId(user.getId().toString())
                    .setConfirmationToken(token)
                    .build();

            responseObserver.onNext(reply);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Unexpected error during registration", e);
            sendError(responseObserver, "INTERNAL_ERROR", "Registration failed: " + e.getMessage());
        }
    }

    @Override
    public void login(LoginRequest req, StreamObserver<LoginResponse> responseObserver) {
        userRepo.findByEmail(req.getEmail()).ifPresentOrElse(user -> {

            // Account status check first (security best practice)
            if (!user.isActive()) {
                sendError(responseObserver, "ACCOUNT_INACTIVE", "Account is disabled or not activated.");
                return;
            }

            // Password verification
            if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
                sendError(responseObserver, "INVALID_CREDENTIALS", "Invalid password.");
                return;
            }

            // Success path
            logLogin(user, req.getDeviceId(), true);

            String accessToken = jwtService.generateAccessToken(user.getId(), user.getRole().name());
            String refreshToken = jwtService.generateRefreshToken(user.getId());

            LoginResponse reply = LoginResponse.newBuilder()
                    .setResponse(successResponse())
                    .setAccessToken(accessToken)
                    .setRefreshToken(refreshToken)
                    .setExpiresAt(Timestamp.newBuilder()
                            .setSeconds(Instant.now().getEpochSecond() + 15 * 60)
                            .build())
                    .setRole(kz.courier.auth.v1.Role.valueOf(user.getRole().name()))
                    .build();

            responseObserver.onNext(reply);
            responseObserver.onCompleted();

        }, () -> sendError(responseObserver, "USER_NOT_FOUND", "No account found with this email."));
    }

    @Override
    public void refreshToken(RefreshTokenRequest req, StreamObserver<RefreshTokenResponse> responseObserver) {
        try {
            Claims claims = jwtService.validateAndGetClaims(req.getRefreshToken());
            UUID userId = UUID.fromString(claims.getSubject());
            User user = userRepo.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));

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

            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Exception ex) {
            log.warn("Refresh token failed: {}", ex.getMessage());
            sendError(responseObserver, "INVALID_REFRESH", "Refresh token invalid or expired");
        }
    }

    @Override
    public void verifyEmail(VerifyEmailRequest req, StreamObserver<Response> responseObserver) {
        tokenRepo.findByTokenAndUsedFalse(req.getToken()).ifPresentOrElse(t -> {
            if (t.getExpiresAt().isBefore(LocalDateTime.now())) {
                sendError(responseObserver, "TOKEN_EXPIRED", "Verification token expired");
                return;
            }

            User u = t.getUser();
            u.setEmailVerified(true);
            userRepo.save(u);
            t.setUsed(true);
            tokenRepo.save(t);

            responseObserver.onNext(successResponse());
            responseObserver.onCompleted();
        }, () -> sendError(responseObserver, "TOKEN_INVALID", "Invalid or already used token"));
    }

    /* ====================== LIST USERS (admin) ====================== */
    @Override
    public void listUsers(ListUsersRequest req, StreamObserver<ListUsersResponse> responseObserver) {
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

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    // ────────────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────────────

    private <T> void sendError(StreamObserver<T> observer, String code, String message) {
        kz.courier.common.v1.Response error = errorResponse(code, message);

        if (observer instanceof StreamObserver<?> typed) {
            StreamObserver<RegisterResponse> reg = (StreamObserver<RegisterResponse>) typed;
            reg.onNext(RegisterResponse.newBuilder().setResponse(error).build());
            observer.onCompleted();
        }
    }

    private Response successResponse() {
        return Response.newBuilder()
                .setSuccess(true)
                .setTimestamp(nowTs())
                .build();
    }

    private Response errorResponse(String code, String message) {
        return Response.newBuilder()
                .setSuccess(false)
                .setError(Error.newBuilder()
                        .setCode(code)
                        .setMessage(message)
                        .build())
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
        InetAddress ipAddress;
        try {
            String ipStr = (deviceId != null && !deviceId.trim().isEmpty())
                    ? deviceId.trim()
                    : "0.0.0.0";

            ipAddress = InetAddress.getByName(ipStr);
        } catch (UnknownHostException e) {
            log.warn("Invalid IP address received: {}, falling back to 0.0.0.0", deviceId, e);
            ipAddress = InetAddress.getLoopbackAddress(); // 127.0.0.1
            // or: ipAddress = InetAddress.getByAddress(new byte[]{0,0,0,0});
        }

        LoginLog log = LoginLog.builder()
                .user(user)
                .ipAddress(ipAddress)
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