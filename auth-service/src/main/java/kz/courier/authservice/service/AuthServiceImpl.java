package kz.courier.authservice.service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.Timestamp;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.jsonwebtoken.Claims;
import kz.courier.auth.v1.AuthServiceGrpc;
import kz.courier.auth.v1.ChangePasswordRequest;
import kz.courier.auth.v1.CreateStaffUserRequest;
import kz.courier.auth.v1.DeleteUserRequest;
import kz.courier.auth.v1.GetUserRequest;
import kz.courier.auth.v1.GetUserResponse;
import kz.courier.auth.v1.ListUsersRequest;
import kz.courier.auth.v1.ListUsersResponse;
import kz.courier.auth.v1.LoginRequest;
import kz.courier.auth.v1.LoginResponse;
import kz.courier.auth.v1.LogoutAllRequest;
import kz.courier.auth.v1.LogoutRequest;
import kz.courier.auth.v1.RefreshTokenRequest;
import kz.courier.auth.v1.RefreshTokenResponse;
import kz.courier.auth.v1.RegisterRequest;
import kz.courier.auth.v1.RegisterResponse;
import kz.courier.auth.v1.UpdateUserRequest;
import kz.courier.auth.v1.VerifyEmailRequest;
import kz.courier.authservice.dto.NotificationEvent;
import kz.courier.authservice.model.ConfirmationToken;
import kz.courier.authservice.model.LoginLog;
import kz.courier.authservice.model.RefreshToken;
import kz.courier.authservice.model.Role;
import kz.courier.authservice.model.TokenType;
import kz.courier.authservice.model.User;
import kz.courier.authservice.repository.ConfirmationTokenRepository;
import kz.courier.authservice.repository.LoginLogRepository;
import kz.courier.authservice.repository.RefreshTokenRepository;
import kz.courier.authservice.repository.UserRepository;
import kz.courier.common.v1.Error;
import kz.courier.common.v1.PaginationResponse;
import kz.courier.common.v1.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

@Slf4j
@GrpcService
@RequiredArgsConstructor
@Transactional
public class AuthServiceImpl extends AuthServiceGrpc.AuthServiceImplBase {

    private final UserRepository userRepo;
    private final ConfirmationTokenRepository tokenRepo;
    private final LoginLogRepository logRepo;
    private final RefreshTokenRepository refreshTokenRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenHasher refreshTokenHasher;
    private final NotificationProducer notificationProducer;
    @Value("${app.api.base-url}")
    private String apiBaseUrl;
    @Value("${app.api.verify-path}")
    private String apiVerifyPath;
    @Value("${jwt.access-expiry-min}")
    private long accessExpiryMin;

    @Override
    public void register(RegisterRequest req, StreamObserver<RegisterResponse> responseObserver) {
        try {
            // ---- validation -------------------------------------------------
            validateEmail(req.getEmail());

            if (userRepo.existsByEmail(req.getEmail())) {
                sendError(responseObserver, "EMAIL_EXISTS", "E-mail already taken");
                return;
            }

            String phone = req.hasPhone() && !req.getPhone().isBlank() ? req.getPhone().trim() : null;
            if (phone != null && userRepo.existsByPhone(phone)) {
                sendError(responseObserver, "PHONE_EXISTS", "Phone already taken");
                return;
            }

            validatePassword(req.getPassword());
            validateNames(req.getFirstName(), req.getLastName());

            // ---- create user ------------------------------------------------
            UUID companyId = req.hasCompanyId() && !req.getCompanyId().isBlank()
                    ? UUID.fromString(req.getCompanyId())
                    : null;
            Role role = resolveRegistrationRole(req, companyId);

            User user = User.builder()
                    .email(req.getEmail())
                    .passwordHash(passwordEncoder.encode(req.getPassword()))
                    .phone(phone)
                    .firstName(req.getFirstName())
                    .lastName(req.getLastName())
                    .pushConsent(req.getPushConsent())
                    .role(role)
                    .companyId(companyId)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            user = userRepo.save(user);
            log.info("Registered auth user userId={} role={}", user.getId(), user.getRole());

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
                            "verify_link", apiBaseUrl + apiVerifyPath + "?token=" + token,
                            "email", user.getEmail()
                    ))
                    .build();
            try {
                notificationProducer.publish(event);
            } catch (Exception notificationError) {
                log.warn("Registration succeeded but notification publish failed for userId={}: {}",
                        user.getId(), notificationError.getMessage());
            }

            // ---- success response -------------------------------------------
            RegisterResponse reply = RegisterResponse.newBuilder()
                    .setResponse(successResponse())
                    .setUserId(user.getId().toString())
                    .build();

            responseObserver.onNext(reply);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Unexpected error during registration", e);
            sendError(responseObserver, "INTERNAL_ERROR", "Registration failed: " + e.getMessage());
        }
    }

    @Override
    public void createStaffUser(CreateStaffUserRequest req, StreamObserver<RegisterResponse> responseObserver) {
        try {
            Role actorRole = Role.valueOf(req.getActorRole().name());
            Role targetRole = Role.valueOf(req.getRole().name());
            validateStaffCreation(actorRole, targetRole);

            validateEmail(req.getEmail());

            if (userRepo.existsByEmail(req.getEmail())) {
                sendError(responseObserver, "EMAIL_EXISTS", "E-mail already taken");
                return;
            }

            if (req.hasPhone() && !req.getPhone().isBlank() && userRepo.existsByPhone(req.getPhone())) {
                sendError(responseObserver, "PHONE_EXISTS", "Phone already taken");
                return;
            }

            validatePassword(req.getPassword());
            validateNames(req.getFirstName(), req.getLastName());

            UUID companyId = req.hasCompanyId() && !req.getCompanyId().isBlank()
                    ? UUID.fromString(req.getCompanyId())
                    : null;
            validateStaffCompanyScope(targetRole, companyId);

            User user = User.builder()
                    .email(req.getEmail())
                    .passwordHash(passwordEncoder.encode(req.getPassword()))
                    .phone(req.hasPhone() && !req.getPhone().isBlank() ? req.getPhone() : null)
                    .firstName(req.getFirstName())
                    .lastName(req.getLastName())
                    .pushConsent(req.getPushConsent())
                    .role(targetRole)
                    .companyId(companyId)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
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

            NotificationEvent event = NotificationEvent.builder()
                    .userId(user.getId().toString())
                    .type("email_verification")
                    .payload(Map.of(
                            "user_name", user.getFirstName(),
                            "verify_link", apiBaseUrl + apiVerifyPath + "?token=" + token,
                            "email", user.getEmail()
                    ))
                    .build();
            try {
                notificationProducer.publish(event);
            } catch (Exception notificationError) {
                log.warn("Staff user created but notification publish failed for userId={}: {}",
                        user.getId(), notificationError.getMessage());
            }

            RegisterResponse reply = RegisterResponse.newBuilder()
                    .setResponse(successResponse())
                    .setUserId(user.getId().toString())
                    .build();

            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            sendError(responseObserver, "INVALID_STAFF_USER", e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error during staff user creation", e);
            sendError(responseObserver, "INTERNAL_ERROR", "Staff user creation failed: " + e.getMessage());
        }
    }

    @Override
    public void login(LoginRequest req, StreamObserver<LoginResponse> responseObserver) {
        userRepo.findByEmail(req.getEmail()).ifPresentOrElse(user -> {

            if (!user.isEmailVerified()){
                sendLoginError(responseObserver, "EMAIL_NOT_VERIFIED", "E-mail not verified");
                return;
            }

            // Account status check first (security best practice)
            if (!user.isActive()) {
                sendLoginError(responseObserver, "ACCOUNT_INACTIVE", "Account is disabled or not activated.");
                return;
            }

            // Password verification
            if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
                sendLoginError(responseObserver, "INVALID_CREDENTIALS", "Invalid password.");
                return;
            }

            String access = jwtService.generateAccessToken(
                user.getId(),
                user.getEmail(),
                user.getRole().name(),
                user.getCompanyId()  // ← need to add this field to User entity
            );
            JwtService.RefreshTokenDetails refresh = jwtService.generateRefreshToken(user.getId());
            storeRefreshToken(user, refresh, UUID.randomUUID());

            // Success path
            try {
                logLogin(user, req.getDeviceId(), true);
            } catch (Exception loginLogError) {
                log.warn("Login succeeded but audit log save failed for userId={}: {}",
                        user.getId(), loginLogError.getMessage());
            }

            LoginResponse reply = LoginResponse.newBuilder()
                    .setResponse(successResponse())
                    .setAccessToken(access)
                    .setRefreshToken(refresh.token())
                    .setExpiresAt(Timestamp.newBuilder()
                            .setSeconds(Instant.now().plusSeconds(accessExpiryMin * 60).getEpochSecond())
                            .build())
                    .setRole(kz.courier.auth.v1.Role.valueOf(user.getRole().name()))
                    .build();

            responseObserver.onNext(reply);
            responseObserver.onCompleted();

        }, () -> sendLoginError(responseObserver, "USER_NOT_FOUND", "No account found with this email."));
    }

    /* ====================== REFRESH TOKEN ====================== */
    @Override
    public void refreshToken(RefreshTokenRequest req, StreamObserver<RefreshTokenResponse> responseObserver) {
        try {
            Claims claims = jwtService.validateAndGetClaims(req.getRefreshToken());
            validateRefreshClaims(claims);
            UUID userId = UUID.fromString(claims.getSubject());
            User user = userRepo.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));
            if (!user.isActive()) {
                sendRefreshError(responseObserver, "ACCOUNT_INACTIVE", "Account is disabled");
                return;
            }

            String tokenHash = refreshTokenHasher.hash(req.getRefreshToken());
            RefreshToken current = refreshTokenRepo.findByTokenHash(tokenHash).orElse(null);
            if (current == null || current.getExpiresAt().isBefore(LocalDateTime.now())) {
                sendRefreshError(responseObserver, "INVALID_REFRESH", "Refresh token invalid or expired");
                return;
            }
            if (current.getRevokedAt() != null) {
                revokeTokenFamily(current.getTokenFamilyId());
                log.warn("Refresh token reuse detected userId={} tokenFamilyId={}",
                        current.getUser().getId(), current.getTokenFamilyId());
                sendRefreshError(responseObserver, "INVALID_REFRESH", "Refresh token invalid or expired");
                return;
            }

            String access = jwtService.generateAccessToken(
                user.getId(),
                user.getEmail(),
                user.getRole().name(),
                user.getCompanyId()  // ← need to add this field to User entity
            );
            JwtService.RefreshTokenDetails newRefresh = jwtService.generateRefreshToken(userId);
            RefreshToken replacement = storeRefreshToken(user, newRefresh, current.getTokenFamilyId());
            LocalDateTime now = LocalDateTime.now();
            current.setRevokedAt(now);
            current.setReplacedByTokenId(replacement.getId());
            current.setUpdatedAt(now);
            refreshTokenRepo.save(current);

            RefreshTokenResponse reply = RefreshTokenResponse.newBuilder()
                    .setResponse(successResponse())
                    .setAccessToken(access)
                    .setRefreshToken(newRefresh.token())
                    .setExpiresAt(Timestamp.newBuilder()
                            .setSeconds(Instant.now().plusSeconds(accessExpiryMin * 60).getEpochSecond())
                            .build())
                    .build();

            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Exception ex) {
            log.warn("Refresh token validation failed");
            sendRefreshError(responseObserver, "INVALID_REFRESH", "Refresh token invalid or expired");
        }
    }

    @Override
    public void logout(LogoutRequest req, StreamObserver<Response> responseObserver) {
        try {
            Claims claims = jwtService.validateAndGetClaims(req.getRefreshToken());
            validateRefreshClaims(claims);

            String tokenHash = refreshTokenHasher.hash(req.getRefreshToken());
            refreshTokenRepo.findByTokenHash(tokenHash).ifPresent(token -> {
                if (token.getRevokedAt() == null) {
                    LocalDateTime now = LocalDateTime.now();
                    token.setRevokedAt(now);
                    token.setUpdatedAt(now);
                    refreshTokenRepo.save(token);
                }
            });

            responseObserver.onNext(successResponse());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.warn("Logout refresh token validation failed");
            sendResponseError(responseObserver, "INVALID_REFRESH", "Refresh token invalid or expired");
        }
    }

    @Override
    public void logoutAll(LogoutAllRequest req, StreamObserver<Response> responseObserver) {
        try {
            UUID actorUserId = UUID.fromString(req.getActorUserId());
            UUID targetUserId = req.hasTargetUserId() && !req.getTargetUserId().isBlank()
                    ? UUID.fromString(req.getTargetUserId())
                    : actorUserId;
            Role actorRole = Role.valueOf(req.getActorRole().name());

            if (!actorUserId.equals(targetUserId) && actorRole != Role.ADMIN && actorRole != Role.SUPER_ADMIN) {
                sendResponseError(responseObserver, "FORBIDDEN", "Only ADMIN or SUPER_ADMIN can revoke another user's sessions");
                return;
            }

            revokeAllUserTokens(targetUserId);
            responseObserver.onNext(successResponse());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            sendResponseError(responseObserver, "INVALID_USER", "Invalid user id or role");
        } catch (Exception e) {
            log.warn("Logout-all failed");
            sendResponseError(responseObserver, "INTERNAL_ERROR", "Could not revoke sessions");
        }
    }

    @Override
    public void verifyEmail(VerifyEmailRequest req, StreamObserver<Response> responseObserver) {
        tokenRepo.findByTokenAndUsedFalse(req.getToken()).ifPresentOrElse(t -> {
            if (t.getExpiresAt().isBefore(LocalDateTime.now())) {
                sendResponseError(responseObserver, "TOKEN_EXPIRED", "Verification token expired");
                return;
            }

            User u = t.getUser();
            u.setEmailVerified(true);
            u.setActive(true);
            userRepo.save(u);
            t.setUsed(true);
            tokenRepo.save(t);

            responseObserver.onNext(successResponse());
            responseObserver.onCompleted();
        }, () -> sendResponseError(responseObserver, "TOKEN_INVALID", "Invalid or already used token"));
    }

    /* ====================== GET USER ====================== */
    @Override
    public void getUser(GetUserRequest req, StreamObserver<GetUserResponse> resp) {
        try {
            UUID userId = UUID.fromString(req.getUserId());
            User user = userRepo.findById(userId)
                    .orElse(null);
            if (user == null) {
                resp.onNext(GetUserResponse.newBuilder()
                        .setResponse(errorResponse("USER_NOT_FOUND", "User not found"))
                        .build());
                resp.onCompleted();
                return;
            }

            GetUserResponse reply = GetUserResponse.newBuilder()
                    .setResponse(successResponse())
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
                    .setIsActive(user.isActive())
                    .build();
            if (user.getCompanyId() != null) {
                reply = reply.toBuilder()
                        .setCompanyId(user.getCompanyId().toString())
                        .build();
            }
            resp.onNext(reply);
            resp.onCompleted();

        } catch (IllegalArgumentException e) {
            resp.onNext(GetUserResponse.newBuilder()
                    .setResponse(errorResponse("INVALID_USER_ID", "Invalid user id"))
                    .build());
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

            try {
                notificationProducer.publish(NotificationEvent.builder()
                        .userId(userId.toString())
                        .type("account_deleted")
                        .payload(Map.of(
                                "user_name",  firstName,
                                "email",      email,
                                "deleted_at", deletedAt
                        ))
                        .build());
            } catch (Exception notificationError) {
                log.warn("Auth user deleted but notification publish failed for userId={}: {}",
                        userId, notificationError.getMessage());
            }

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

        usersPage.forEach(u -> {
            GetUserResponse.Builder userBuilder = GetUserResponse.newBuilder()
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
                    .setIsActive(u.isActive());
            if (u.getCompanyId() != null) {
                userBuilder.setCompanyId(u.getCompanyId().toString());
            }
            builder.addUsers(userBuilder.build());
        });

        resp.onNext(builder.build());
        resp.onCompleted();
    }

    /* ====================== HELPERS ====================== */
    private RefreshToken storeRefreshToken(User user, JwtService.RefreshTokenDetails details, UUID tokenFamilyId) {
        RefreshToken token = RefreshToken.builder()
                .id(details.tokenId())
                .user(user)
                .tokenHash(refreshTokenHasher.hash(details.token()))
                .issuedAt(LocalDateTime.ofInstant(details.issuedAt(), ZoneOffset.UTC))
                .expiresAt(LocalDateTime.ofInstant(details.expiresAt(), ZoneOffset.UTC))
                .tokenFamilyId(tokenFamilyId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        return refreshTokenRepo.save(token);
    }

    private void validateRefreshClaims(Claims claims) {
        if (!"refresh".equals(claims.get("token_type", String.class))) {
            throw new IllegalArgumentException("Invalid token type");
        }
        if (claims.getId() == null || claims.getId().isBlank()) {
            throw new IllegalArgumentException("Missing token id");
        }
    }

    private void revokeTokenFamily(UUID tokenFamilyId) {
        LocalDateTime now = LocalDateTime.now();
        refreshTokenRepo.findByTokenFamilyId(tokenFamilyId).forEach(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(now);
                token.setUpdatedAt(now);
                refreshTokenRepo.save(token);
            }
        });
    }

    private void revokeAllUserTokens(UUID userId) {
        LocalDateTime now = LocalDateTime.now();
        refreshTokenRepo.findByUserIdAndRevokedAtIsNull(userId).forEach(token -> {
            token.setRevokedAt(now);
            token.setUpdatedAt(now);
            refreshTokenRepo.save(token);
        });
    }

    private void fail(StreamObserver<?> obs, String code, String msg) {
        obs.onError(Status.INVALID_ARGUMENT
                .withDescription(code + ": " + msg)
                .asRuntimeException());
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

    private void sendLoginError(StreamObserver<LoginResponse> observer, String code, String message) {
        observer.onNext(LoginResponse.newBuilder()
                .setResponse(errorResponse(code, message))
                .build());
        observer.onCompleted();
    }

    private void sendRefreshError(StreamObserver<RefreshTokenResponse> observer, String code, String message) {
        observer.onNext(RefreshTokenResponse.newBuilder()
                .setResponse(errorResponse(code, message))
                .build());
        observer.onCompleted();
    }

    private void sendResponseError(StreamObserver<Response> observer, String code, String message) {
        observer.onNext(errorResponse(code, message));
        observer.onCompleted();
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

    private Role resolveRegistrationRole(RegisterRequest req, UUID companyId) {

        Role requestedRole = Role.valueOf(req.getRole().name());

        if (companyId == null) {
            if (requestedRole == Role.CLIENT || requestedRole == Role.COURIER) {
                return requestedRole;
            }

            throw new IllegalArgumentException("Public registration only supports CLIENT or COURIER");
        }

        if (requestedRole == Role.DIRECTOR || requestedRole == Role.MANAGER) {
            return requestedRole;
        }

        throw new IllegalArgumentException("Company-scoped registration only supports DIRECTOR or MANAGER");
    }

    private void validateStaffCreation(Role actorRole, Role targetRole) {
        if (actorRole != Role.SUPER_ADMIN) {
            throw new IllegalArgumentException("Only SUPER_ADMIN can create staff users");
        }

        if (targetRole != Role.ADMIN) {
            throw new IllegalArgumentException("Staff role must be ADMIN");
        }
    }

    private void validateStaffCompanyScope(Role targetRole, UUID companyId) {
        if (companyId != null) {
            throw new IllegalArgumentException("companyId must be empty for ADMIN");
        }
    }
}
