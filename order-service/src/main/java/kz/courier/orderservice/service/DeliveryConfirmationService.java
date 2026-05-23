package kz.courier.orderservice.service;

import kz.courier.orderservice.dto.DeliveryConfirmationCodeView;
import kz.courier.orderservice.dto.DeliveryConfirmationIssueResult;
import kz.courier.orderservice.exception.OrderNotFoundException;
import kz.courier.orderservice.exception.OrderServiceException;
import kz.courier.orderservice.model.DeliveryConfirmationCode;
import kz.courier.orderservice.model.Order;
import kz.courier.orderservice.model.OrderStatus;
import kz.courier.orderservice.repository.DeliveryConfirmationCodeRepository;
import kz.courier.orderservice.repository.OrderRepository;
import kz.courier.orderservice.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/**
 * Handles delivery confirmation as a domain workflow.
 *
 * <p>The workflow is intentionally transaction-bound: status change, OTP
 * creation, attempts update and final delivery transition are committed
 * atomically. This prevents split-brain cases such as a generated OTP for an
 * order that is still not in confirmation-pending state.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryConfirmationService {

    private static final int CODE_DIGITS = 6;
    private static final int MAX_ATTEMPTS = 5;
    private static final int TTL_MINUTES = 10;
    private static final int RESEND_COOLDOWN_SECONDS = 60;
    private static final Set<String> DELIVERY_VERIFICATION_ROLES = Set.of("COURIER", "ADMIN", "SUPER_ADMIN");
    private static final Set<String> PRIVILEGED_ROLES = Set.of("ADMIN", "SUPER_ADMIN");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final OrderRepository orderRepository;
    private final DeliveryConfirmationCodeRepository codeRepository;
    private final DeliveryConfirmationNotificationPort notificationPort;

    /**
     * Marks courier arrival and issues a one-time code for the customer.
     *
     * <p>The OTP is six digits because it is easy to read over the phone or in a
     * mobile UI while still giving 1,000,000 combinations. Security is provided
     * by a short 10-minute TTL, five-attempt limit and server-side hashing.
     */
    @Transactional
    public DeliveryConfirmationIssueResult confirmArrival(UUID orderId, AuthenticatedUser caller) {
        requireAdmin(caller);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId.toString()));
        UUID actorId = parseUuid(caller.userId(), "caller userId");
        log.warn("[DeliveryConfirmation] Admin issued OTP directly orderId={} actorId={}",
                orderId, actorId);
        return issueCode(order, actorId, false);
    }

    /**
     * Internal event-driven entry point used by logistics assignment events.
     *
     * <p>Logistics-service owns assignment data and has already validated that
     * this courier is assigned before it publishes ARRIVED. Order-service does
     * not duplicate courier assignment state; it only issues and verifies OTP.
     */
    @Transactional
    public DeliveryConfirmationIssueResult confirmArrivalFromAssignment(UUID orderId, UUID courierId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId.toString()));
        return issueCode(order, courierId, true);
    }

    /**
     * Resends the current OTP or regenerates it when the previous code expired.
     *
     * <p>The public REST entry point lives in logistics-service, where assignment
     * ownership is enforced. Order-service only applies OTP lifecycle rules.
     */
    @Transactional
    public DeliveryConfirmationIssueResult resendCodeFromAssignment(UUID orderId, UUID courierId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId.toString()));

        if (order.getStatus() != OrderStatus.DELIVERY_CONFIRMATION_PENDING) {
            throw new OrderServiceException("INVALID_STATUS",
                    "Confirmation code can be resent only while delivery confirmation is pending");
        }

        DeliveryConfirmationCode latest = codeRepository
                .findTopByOrder_IdAndConsumedFalseOrderByCreatedAtDesc(orderId)
                .orElseThrow(() -> new OrderServiceException("OTP_NOT_FOUND",
                        "No active confirmation code found for this order"));

        enforceResendCooldown(latest);

        if (isCodeStillUsable(latest)) {
            notificationPort.sendDeliveryConfirmationCode(order, latest.getDisplayCode(), latest.getExpiresAt());
            log.info("[DeliveryConfirmation] OTP resent orderId={} courierId={} expiresAt={}",
                    orderId, courierId, latest.getExpiresAt());
            return new DeliveryConfirmationIssueResult(order.getStatus(), latest.getExpiresAt(), false);
        }

        return issueCode(order, courierId, true);
    }

    private DeliveryConfirmationIssueResult issueCode(Order order, UUID courierId, boolean idempotent) {
        UUID orderId = order.getId();
        if (isTerminal(order.getStatus())) {
            throw new OrderServiceException("TERMINAL_STATUS",
                    "Order is already in terminal status: " + order.getStatus());
        }
        if (order.getStatus() != OrderStatus.IN_TRANSIT
                && order.getStatus() != OrderStatus.PICKED_UP
                && order.getStatus() != OrderStatus.DELIVERY_CONFIRMATION_PENDING) {
            throw new OrderServiceException("INVALID_STATUS_TRANSITION",
                    "Confirmation can be requested only after courier pickup or while in transit");
        }

        var activeCode = codeRepository.findTopByOrder_IdAndConsumedFalseOrderByCreatedAtDesc(orderId)
                .filter(this::isCodeStillUsable);
        if (activeCode.isPresent()) {
            if (idempotent) {
                log.info("[DeliveryConfirmation] Active OTP already exists orderId={} expiresAt={}",
                        orderId, activeCode.get().getExpiresAt());
                return new DeliveryConfirmationIssueResult(order.getStatus(), activeCode.get().getExpiresAt(), false);
            }
                    throw new OrderServiceException("OTP_ALREADY_ACTIVE",
                            "A confirmation code is already active for this order");
        }

        String code = generateNumericCode();
        String salt = generateSalt();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(TTL_MINUTES);

        order.setStatus(OrderStatus.DELIVERY_CONFIRMATION_PENDING);
        DeliveryConfirmationCode confirmationCode = DeliveryConfirmationCode.builder()
                .order(order)
                .courierId(courierId)
                .salt(salt)
                .codeHash(hashCode(salt, code))
                .displayCode(code)
                .expiresAt(expiresAt)
                .maxAttempts(MAX_ATTEMPTS)
                .build();

        codeRepository.save(confirmationCode);
        orderRepository.save(order);
        notificationPort.sendDeliveryConfirmationCode(order, code, expiresAt);

        log.info("[DeliveryConfirmation] OTP issued orderId={} courierId={} expiresAt={}",
                orderId, courierId, expiresAt);
        return new DeliveryConfirmationIssueResult(order.getStatus(), expiresAt, true);
    }

    /**
     * Returns the active code for in-app fallback display.
     *
     * <p>Only the order owner or an admin may retrieve the code, and only while
     * the order waits for delivery confirmation. This prevents IDOR leaks.
     */
    @Transactional(readOnly = true)
    public DeliveryConfirmationCodeView getCodeForCustomer(UUID orderId, AuthenticatedUser caller) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId.toString()));
        requireOrderOwnerOrAdmin(order, caller);

        if (order.getStatus() != OrderStatus.DELIVERY_CONFIRMATION_PENDING) {
            throw new OrderServiceException("INVALID_STATUS",
                    "Confirmation code is available only while delivery confirmation is pending");
        }

        DeliveryConfirmationCode code = codeRepository.findTopByOrder_IdAndConsumedFalseOrderByCreatedAtDesc(orderId)
                .orElseThrow(() -> new OrderServiceException("OTP_NOT_FOUND",
                        "No active confirmation code found for this order"));
        if (!isCodeStillUsable(code)) {
            throw new OrderServiceException("OTP_EXPIRED", "Confirmation code has expired");
        }

        return new DeliveryConfirmationCodeView(
                code.getDisplayCode(),
                code.getExpiresAt(),
                code.attemptsRemaining());
    }

    /**
     * Verifies courier-entered OTP and moves the order to DELIVERED.
     *
     * <p>The active row is pessimistically locked so concurrent retries cannot
     * bypass the attempts counter or deliver the same order twice.
     */
    @Transactional
    public OrderStatus verifyCode(UUID orderId, String rawCode, AuthenticatedUser caller) {
        requireDeliveryVerifier(caller);
        if (rawCode == null || !rawCode.matches("\\d{" + CODE_DIGITS + "}")) {
            throw new OrderServiceException("INVALID_OTP_FORMAT",
                    "Confirmation code must contain exactly " + CODE_DIGITS + " digits");
        }

        DeliveryConfirmationCode code = codeRepository
                .findFirstByOrder_IdAndConsumedFalseOrderByCreatedAtDesc(orderId)
                .orElseThrow(() -> new OrderServiceException("OTP_NOT_FOUND",
                        "No active confirmation code found for this order"));
        Order order = code.getOrder();

        if (order.getStatus() != OrderStatus.DELIVERY_CONFIRMATION_PENDING) {
            throw new OrderServiceException("INVALID_STATUS",
                    "Order is not waiting for delivery confirmation");
        }
        if (OffsetDateTime.now(ZoneOffset.UTC).isAfter(code.getExpiresAt())) {
            throw new OrderServiceException("OTP_EXPIRED", "Confirmation code has expired");
        }
        if (code.getAttempts() >= code.getMaxAttempts()) {
            throw new OrderServiceException("OTP_ATTEMPTS_EXCEEDED",
                    "Confirmation code attempt limit exceeded");
        }

        if (!MessageDigest.isEqual(
                hashCode(code.getSalt(), rawCode).getBytes(StandardCharsets.UTF_8),
                code.getCodeHash().getBytes(StandardCharsets.UTF_8))) {
            code.setAttempts(code.getAttempts() + 1);
            codeRepository.save(code);
            log.warn("[DeliveryConfirmation] Invalid OTP orderId={} attemptsRemaining={}",
                    orderId, code.attemptsRemaining());
            throw new OrderServiceException("INVALID_OTP",
                    "Invalid confirmation code. Attempts remaining: " + code.attemptsRemaining());
        }

        code.setConsumed(true);
        order.setStatus(OrderStatus.DELIVERED);
        codeRepository.save(code);
        orderRepository.save(order);

        log.info("[DeliveryConfirmation] Order delivered orderId={} courierId={}",
                orderId, caller.userId());
        return order.getStatus();
    }

    private boolean isCodeStillUsable(DeliveryConfirmationCode code) {
        return !code.isConsumed()
                && code.getAttempts() < code.getMaxAttempts()
                && OffsetDateTime.now(ZoneOffset.UTC).isBefore(code.getExpiresAt());
    }

    private void enforceResendCooldown(DeliveryConfirmationCode code) {
        if (code.getCreatedAt() == null) {
            return;
        }
        OffsetDateTime nextAllowedAt = code.getCreatedAt().plusSeconds(RESEND_COOLDOWN_SECONDS);
        if (OffsetDateTime.now(ZoneOffset.UTC).isBefore(nextAllowedAt)) {
            throw new OrderServiceException("OTP_RESEND_RATE_LIMITED",
                    "Please wait before requesting another confirmation code");
        }
    }

    private boolean isTerminal(OrderStatus status) {
        return status == OrderStatus.DELIVERED
                || status == OrderStatus.CANCELLED
                || status == OrderStatus.REJECTED;
    }

    private void requireDeliveryVerifier(AuthenticatedUser caller) {
        if (caller == null || !caller.hasRole(DELIVERY_VERIFICATION_ROLES.toArray(String[]::new))) {
            throw new OrderServiceException("FORBIDDEN",
                    "Only courier or admin can perform delivery confirmation action");
        }
    }

    private void requireAdmin(AuthenticatedUser caller) {
        if (caller == null || !caller.hasRole(PRIVILEGED_ROLES.toArray(String[]::new))) {
            throw new OrderServiceException("FORBIDDEN",
                    "Only admin can issue delivery confirmation code directly in order-service");
        }
    }

    private void requireOrderOwnerOrAdmin(Order order, AuthenticatedUser caller) {
        if (caller != null && caller.hasRole(PRIVILEGED_ROLES.toArray(String[]::new))) {
            return;
        }

        UUID callerId = parseUuid(caller != null ? caller.userId() : null, "caller userId");
        if (!callerId.equals(order.getAuthorId())) {
            throw new OrderServiceException("FORBIDDEN",
                    "Only order owner can view delivery confirmation code");
        }
    }

    private String generateNumericCode() {
        int upperBound = (int) Math.pow(10, CODE_DIGITS);
        int value = SECURE_RANDOM.nextInt(upperBound);
        return String.format("%0" + CODE_DIGITS + "d", value);
    }

    private String generateSalt() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String hashCode(String salt, String rawCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((salt + ":" + rawCode).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash delivery confirmation code", e);
        }
    }

    private UUID parseUuid(String rawValue, String fieldName) {
        try {
            return UUID.fromString(rawValue);
        } catch (Exception e) {
            throw new OrderServiceException("INVALID_ARGUMENT", "Invalid UUID for " + fieldName);
        }
    }
}
