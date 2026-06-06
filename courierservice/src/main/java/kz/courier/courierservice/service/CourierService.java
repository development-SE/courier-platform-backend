package kz.courier.courierservice.service;

import kz.courier.courierservice.dto.CourierDto;
import kz.courier.courierservice.entity.CourierProfile;
import kz.courier.courierservice.entity.CourierType;
import kz.courier.courierservice.entity.CourierWorkSchedule;
import kz.courier.courierservice.entity.EmploymentStatus;
import kz.courier.courierservice.entity.CourierDocument;
import kz.courier.courierservice.entity.DocumentStatus;
import kz.courier.courierservice.entity.DocumentType;
import kz.courier.courierservice.entity.TransportType;
import kz.courier.courierservice.exception.BusinessException;
import kz.courier.courierservice.exception.CourierNotFoundException;
import kz.courier.courierservice.grpc.AuthGrpcClient;
import kz.courier.courierservice.repository.CourierProfileRepository;
import kz.courier.courierservice.repository.CourierDocumentRepository;
import kz.courier.courierservice.security.GatewayPrincipalProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class CourierService {

    private static final List<String> PRIVILEGED_ROLES =
            List.of("ADMIN", "SUPER_ADMIN", "MANAGER", "DIRECTOR");
    private static final List<String> GLOBAL_ADMIN_ROLES =
            List.of("ADMIN", "SUPER_ADMIN");
    private static final List<String> COMPANY_SCOPED_ROLES =
            List.of("MANAGER", "DIRECTOR");

    private final CourierProfileRepository courierProfileRepository;
    private final GatewayPrincipalProvider gatewayPrincipalProvider;
    private final AuthGrpcClient authGrpcClient;
    private final CourierDocumentRepository courierDocumentRepository;

    public CourierDto.CourierProfileResponse create(CourierDto.CreateCourierRequest req) {
        GatewayPrincipalProvider.GatewayPrincipal principal =
                gatewayPrincipalProvider.requireCurrentPrincipal();

        UUID resolvedUserId = req.userId();
        if (hasAnyRole(principal, "COURIER")) {
            req = normalizeSelfServiceCreate(req, gatewayPrincipalProvider.requireCurrentUserId());
            resolvedUserId = req.userId();
        } else if (hasAnyRole(principal, "ADMIN", "SUPER_ADMIN")) {
            req = normalizePrivilegedCreate(req);
            resolvedUserId = req.userId();
        } else if (hasAnyRole(principal, "DIRECTOR", "MANAGER")) {
            req = normalizeCompanyScopedCreate(req, requireCallerCompanyId(principal));
            resolvedUserId = req.userId();
        } else {
            throw new BusinessException("FORBIDDEN",
                    "Only couriers or privileged users may create courier profiles");
        }

        requireValidCourierAuthUser(resolvedUserId);

        if (courierProfileRepository.existsById(resolvedUserId)) {
            throw new BusinessException("COURIER_ALREADY_EXISTS",
                    "Courier profile already exists for user " + resolvedUserId);
        }

        CourierProfile profile = CourierProfile.builder()
                .id(resolvedUserId)
                .companyId(req.companyId())
                .courierType(req.courierType())
                .employmentStatus(req.employmentStatus())
                .transportType(req.transportType())
                .verified(req.isVerified())
                .canTakeOrders(req.canTakeOrders())
                .maxActiveOrders(req.maxActiveOrders() != null ? req.maxActiveOrders() : 1)
                .notes(blankToNull(req.notes()))
                .schedules(new ArrayList<>())
                .build();

        validateScheduleExpectation(profile.getCourierType(), req.schedules());
        replaceSchedules(profile, req.schedules());
        return toResponse(courierProfileRepository.save(profile));
    }

    @Transactional(readOnly = true)
    public CourierDto.CourierProfileResponse get(UUID courierId) {
        CourierProfile profile = findWithSchedules(courierId);
        requireCanReadProfile(profile);
        return toResponse(profile);
    }

    @Transactional(readOnly = true)
    public Page<CourierDto.CourierProfileResponse> list(UUID companyId, int page, int size) {
        GatewayPrincipalProvider.GatewayPrincipal principal =
                gatewayPrincipalProvider.requireCurrentPrincipal();
        UUID effectiveCompanyId = companyId;

        if (hasAnyRole(principal, "ADMIN", "SUPER_ADMIN")) {
            // Global admins may list all couriers or filter by any company.
        } else if (hasAnyRole(principal, "DIRECTOR", "MANAGER")) {
            UUID callerCompanyId = requireCallerCompanyId(principal);
            if (companyId != null && !companyId.equals(callerCompanyId)) {
                throw new BusinessException("FORBIDDEN",
                        "Company-scoped users may only list couriers from their own company");
            }
            effectiveCompanyId = callerCompanyId;
        } else {
            throw new BusinessException("FORBIDDEN",
                    "Only privileged roles may list courier profiles");
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return courierProfileRepository.findAllFiltered(effectiveCompanyId, pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public CourierDto.CourierProfileResponse getByUserId(UUID userId) {
        CourierProfile profile = courierProfileRepository.findWithSchedulesById(userId)
                .orElseThrow(() -> new CourierNotFoundException(userId));
        requireCanReadProfile(profile);
        return toResponse(profile);
    }

    @Transactional(readOnly = true)
    public CourierDto.CourierProfileResponse getCurrent() {
        UUID currentUserId = gatewayPrincipalProvider.requireCurrentUserId();
        return courierProfileRepository.findWithSchedulesById(currentUserId)
                .map(this::toResponse)
                .orElseGet(() -> buildSelfRegisteredContractor(currentUserId));
    }

    public CourierDto.CourierProfileResponse update(UUID courierId, CourierDto.UpdateCourierRequest req) {
        CourierProfile profile = findWithSchedules(courierId);
        GatewayPrincipalProvider.GatewayPrincipal principal =
                gatewayPrincipalProvider.requireCurrentPrincipal();

        if (hasAnyRole(principal, "ADMIN", "SUPER_ADMIN")) {
            applyPrivilegedUpdate(profile, req);
        } else if (hasAnyRole(principal, "DIRECTOR", "MANAGER")) {
            requireSameCompany(profile, requireCallerCompanyId(principal));
            applyCompanyScopedUpdate(profile, req);
        } else if (hasAnyRole(principal, "COURIER")) {
            requireSelf(profile.getId(), "update your courier profile");
            applyCourierSelfUpdate(profile, req);
        } else {
            throw new BusinessException("FORBIDDEN",
                    "You do not have permission to update courier profiles");
        }

        return toResponse(courierProfileRepository.save(profile));
    }

    @Transactional(readOnly = true)
    public CourierDto.EligibilityResponse getEligibility(UUID courierId, OffsetDateTime at) {
        CourierProfile profile = findWithSchedules(courierId);
        requirePrivilegedReader(profile);
        OffsetDateTime evaluatedAt = at != null ? at : OffsetDateTime.now();

        if (profile.getEmploymentStatus() != EmploymentStatus.ACTIVE) {
            return ineligible(profile, evaluatedAt, "COURIER_NOT_ACTIVE",
                    "Courier is not in ACTIVE employment status", false);
        }
        if (!profile.isVerified()) {
            return ineligible(profile, evaluatedAt, "COURIER_NOT_VERIFIED",
                    "Courier profile is not verified", false);
        }
        if (!profile.isCanTakeOrders()) {
            return ineligible(profile, evaluatedAt, "COURIER_DISABLED",
                    "Courier is currently not allowed to take orders", false);
        }
        
        List<DocumentType> required = getRequiredDocumentTypes(profile.getTransportType());
        List<CourierDocument> docs = profile.getDocuments();
        boolean allApproved = required.stream().allMatch(reqType -> docs != null && docs.stream()
                .anyMatch(d -> d.getDocumentType() == reqType && d.getStatus() == DocumentStatus.APPROVED));
        if (!allApproved) {
            return ineligible(profile, evaluatedAt, "DOCUMENTS_NOT_APPROVED",
                    "Not all required onboarding documents are approved", false);
        }

        if (profile.getCourierType() == CourierType.EMPLOYEE) {
            boolean withinSchedule = isWithinSchedule(profile, evaluatedAt);
            if (!withinSchedule) {
                return ineligible(profile, evaluatedAt, "OUTSIDE_SCHEDULE",
                        "Employee courier is outside the configured work schedule", false);
            }
            return eligible(profile, evaluatedAt, true,
                    "EMPLOYEE_SCHEDULE_ACTIVE", "Courier is eligible within the active schedule");
        }

        return eligible(profile, evaluatedAt, false,
                "CONTRACTOR_AVAILABLE", "Contractor courier is eligible");
    }

    private AuthGrpcClient.AuthUser requireValidCourierAuthUser(UUID userId) {
        if (userId == null) {
            throw new BusinessException("INVALID_ARGUMENT", "userId is required");
        }

        AuthGrpcClient.AuthUser user = authGrpcClient.getUser(userId);
        if (!"COURIER".equals(user.role())) {
            throw new BusinessException("USER_NOT_COURIER",
                    "Courier profiles can only be created for auth users with role COURIER");
        }
        if (!user.active()) {
            throw new BusinessException("AUTH_USER_INACTIVE",
                    "Courier profile cannot be created for an inactive auth user");
        }
        return user;
    }

    private CourierDto.CreateCourierRequest normalizeSelfServiceCreate(
            CourierDto.CreateCourierRequest req,
            UUID callerUserId) {

        UUID requestUserId = req.userId();
        if (requestUserId != null && !callerUserId.equals(requestUserId)) {
            throw new BusinessException("FORBIDDEN",
                    "Couriers can only create their own profile");
        }

        return new CourierDto.CreateCourierRequest(
                callerUserId,
                null,
                CourierType.CONTRACTOR,
                EmploymentStatus.ONBOARDING,
                req.transportType(),
                false,
                false,
                1,
                req.notes(),
                null
        );
    }

    private CourierDto.CreateCourierRequest normalizePrivilegedCreate(CourierDto.CreateCourierRequest req) {
        requireCreateBasics(req);
        return req;
    }

    private CourierDto.CreateCourierRequest normalizeCompanyScopedCreate(
            CourierDto.CreateCourierRequest req,
            UUID callerCompanyId) {

        requireCreateBasics(req);
        UUID targetCompanyId = req.companyId() != null ? req.companyId() : callerCompanyId;
        if (!callerCompanyId.equals(targetCompanyId)) {
            throw new BusinessException("FORBIDDEN",
                    "Company-scoped users may only create couriers for their own company");
        }

        return new CourierDto.CreateCourierRequest(
                req.userId(),
                callerCompanyId,
                req.courierType(),
                req.employmentStatus(),
                req.transportType(),
                req.isVerified(),
                req.canTakeOrders(),
                req.maxActiveOrders(),
                req.notes(),
                req.schedules()
        );
    }

    private void requireCreateBasics(CourierDto.CreateCourierRequest req) {
        if (req.userId() == null) {
            throw new BusinessException("INVALID_ARGUMENT", "userId is required");
        }
        if (req.courierType() == null) {
            throw new BusinessException("INVALID_ARGUMENT", "courierType is required");
        }
        if (req.employmentStatus() == null) {
            throw new BusinessException("INVALID_ARGUMENT", "employmentStatus is required");
        }
    }

    private void requireCanReadProfile(CourierProfile profile) {
        GatewayPrincipalProvider.GatewayPrincipal principal =
                gatewayPrincipalProvider.requireCurrentPrincipal();
        if (hasAnyRole(principal, "ADMIN", "SUPER_ADMIN")) {
            return;
        }
        if (hasAnyRole(principal, "DIRECTOR", "MANAGER")) {
            requireSameCompany(profile, requireCallerCompanyId(principal));
            return;
        }
        if (hasAnyRole(principal, "COURIER")) {
            requireSelf(profile.getId(), "read your courier profile");
            return;
        }
        throw new BusinessException("FORBIDDEN",
                "You do not have permission to read courier profiles");
    }

    private void requirePrivilegedReader(CourierProfile profile) {
        GatewayPrincipalProvider.GatewayPrincipal principal =
                gatewayPrincipalProvider.requireCurrentPrincipal();
        if (hasAnyRole(principal, GLOBAL_ADMIN_ROLES.toArray(String[]::new))) {
            return;
        }
        if (hasAnyRole(principal, COMPANY_SCOPED_ROLES.toArray(String[]::new))) {
            requireSameCompany(profile, requireCallerCompanyId(principal));
            return;
        }
        throw new BusinessException("FORBIDDEN",
                "Only privileged roles may read courier eligibility");
    }

    private void applyPrivilegedUpdate(CourierProfile profile, CourierDto.UpdateCourierRequest req) {
        if (req.companyId() != null) {
            profile.setCompanyId(req.companyId());
        }
        if (req.courierType() != null) {
            profile.setCourierType(req.courierType());
        }
        if (req.employmentStatus() != null) {
            profile.setEmploymentStatus(req.employmentStatus());
        }
        if (req.transportType() != null) {
            profile.setTransportType(req.transportType());
            reEvaluateVerification(profile);
        }
        if (req.isVerified() != null) {
            profile.setVerified(req.isVerified());
        }
        if (req.canTakeOrders() != null) {
            profile.setCanTakeOrders(req.canTakeOrders());
        }
        if (req.maxActiveOrders() != null) {
            profile.setMaxActiveOrders(req.maxActiveOrders());
        }
        if (req.notes() != null) {
            profile.setNotes(blankToNull(req.notes()));
        }
        if (req.schedules() != null) {
            validateScheduleExpectation(profile.getCourierType(), req.schedules());
            replaceSchedules(profile, req.schedules());
        }
    }

    private void applyCompanyScopedUpdate(CourierProfile profile, CourierDto.UpdateCourierRequest req) {
        if (req.companyId() != null && !req.companyId().equals(profile.getCompanyId())) {
            throw new BusinessException("FORBIDDEN",
                    "Company-scoped users cannot move couriers between companies");
        }
        applyPrivilegedUpdate(profile, new CourierDto.UpdateCourierRequest(
                null,
                req.courierType(),
                req.employmentStatus(),
                req.transportType(),
                req.isVerified(),
                req.canTakeOrders(),
                req.maxActiveOrders(),
                req.notes(),
                req.schedules()
        ));
    }

    private void applyCourierSelfUpdate(CourierProfile profile, CourierDto.UpdateCourierRequest req) {
        if (req.companyId() != null
                || req.courierType() != null
                || req.employmentStatus() == EmploymentStatus.ACTIVE
                || req.isVerified() != null
                || Boolean.TRUE.equals(req.canTakeOrders())
                || req.schedules() != null) {
            throw new BusinessException("FORBIDDEN",
                    "Couriers cannot self-verify, activate, join companies, enable orders, or set schedules");
        }
        if (req.transportType() != null) {
            profile.setTransportType(req.transportType());
            reEvaluateVerification(profile);
        }
        if (req.notes() != null) {
            profile.setNotes(blankToNull(req.notes()));
        }
    }

    private UUID requireCallerCompanyId(GatewayPrincipalProvider.GatewayPrincipal principal) {
        AuthGrpcClient.AuthUser caller = authGrpcClient.getUser(UUID.fromString(principal.userId()));
        if (caller.companyId() == null) {
            throw new BusinessException("FORBIDDEN",
                    "Company-scoped users must belong to a company");
        }
        return caller.companyId();
    }

    private void requireSameCompany(CourierProfile profile, UUID callerCompanyId) {
        if (profile.getCompanyId() == null || !profile.getCompanyId().equals(callerCompanyId)) {
            throw new BusinessException("FORBIDDEN",
                    "Company-scoped users may only manage couriers from their own company");
        }
    }

    private void requireSelf(UUID profileUserId, String action) {
        UUID currentUserId = gatewayPrincipalProvider.requireCurrentUserId();
        if (!currentUserId.equals(profileUserId)) {
            throw new BusinessException("FORBIDDEN",
                    "Couriers may only " + action);
        }
    }

    private boolean hasAnyRole(GatewayPrincipalProvider.GatewayPrincipal principal, String... roles) {
        Set<String> currentRoles = principal.roles();
        for (String role : roles) {
            if (currentRoles.contains(role)) {
                return true;
            }
        }
        return false;
    }

    private CourierDto.CourierProfileResponse buildSelfRegisteredContractor(UUID userId) {
        if (!gatewayPrincipalProvider.hasAnyRole("COURIER")) {
            throw new CourierNotFoundException(userId);
        }

        return CourierDto.CourierProfileResponse.builder()
                .id(userId)
                .companyId(null)
                .courierType(CourierType.CONTRACTOR)
                .employmentStatus(EmploymentStatus.ONBOARDING)
                .transportType(null)
                .isVerified(false)
                .canTakeOrders(false)
                .maxActiveOrders(1)
                .notes("Contractor profile is being prepared. Complete courier onboarding to start taking orders.")
                .schedules(List.of())
                .createdAt(null)
                .updatedAt(null)
                .build();
    }

    private CourierProfile findWithSchedules(UUID courierId) {
        return courierProfileRepository.findWithSchedulesById(courierId)
                .orElseThrow(() -> new CourierNotFoundException(courierId));
    }

    private void validateScheduleExpectation(CourierType courierType, List<CourierDto.ScheduleRequest> schedules) {
        if (courierType == CourierType.EMPLOYEE && (schedules == null || schedules.isEmpty())) {
            throw new BusinessException("SCHEDULE_REQUIRED",
                    "Employee couriers must have at least one active work schedule");
        }
    }

    private void replaceSchedules(CourierProfile profile, List<CourierDto.ScheduleRequest> schedules) {
        profile.getSchedules().clear();
        if (schedules == null) {
            return;
        }

        for (CourierDto.ScheduleRequest schedule : schedules) {
            if (!schedule.startTime().isBefore(schedule.endTime())) {
                throw new BusinessException("INVALID_SCHEDULE",
                        "Schedule startTime must be before endTime");
            }
            profile.getSchedules().add(CourierWorkSchedule.builder()
                    .courier(profile)
                    .weekday(schedule.weekday())
                    .startTime(schedule.startTime())
                    .endTime(schedule.endTime())
                    .timezone(schedule.timezone().trim())
                    .active(schedule.active())
                    .build());
        }
    }

    private boolean isWithinSchedule(CourierProfile profile, OffsetDateTime evaluatedAt) {
        return profile.getSchedules().stream()
                .filter(CourierWorkSchedule::isActive)
                .anyMatch(schedule -> isScheduleMatch(schedule, evaluatedAt));
    }

    private boolean isScheduleMatch(CourierWorkSchedule schedule, OffsetDateTime evaluatedAt) {
        ZoneId zoneId = ZoneId.of(schedule.getTimezone());
        ZonedDateTime zonedDateTime = evaluatedAt.atZoneSameInstant(zoneId);
        return zonedDateTime.getDayOfWeek() == schedule.getWeekday()
                && !zonedDateTime.toLocalTime().isBefore(schedule.getStartTime())
                && zonedDateTime.toLocalTime().isBefore(schedule.getEndTime());
    }

    private CourierDto.EligibilityResponse eligible(CourierProfile profile,
                                                    OffsetDateTime evaluatedAt,
                                                    boolean withinSchedule,
                                                    String reasonCode,
                                                    String message) {
        return buildEligibility(profile, evaluatedAt, true, reasonCode, message, withinSchedule);
    }

    private CourierDto.EligibilityResponse ineligible(CourierProfile profile,
                                                      OffsetDateTime evaluatedAt,
                                                      String reasonCode,
                                                      String message,
                                                      boolean withinSchedule) {
        return buildEligibility(profile, evaluatedAt, false, reasonCode, message, withinSchedule);
    }

    private CourierDto.EligibilityResponse buildEligibility(CourierProfile profile,
                                                            OffsetDateTime evaluatedAt,
                                                            boolean eligible,
                                                            String reasonCode,
                                                            String message,
                                                            boolean withinSchedule) {
        LocalDateTime localDateTime = evaluatedAt.toLocalDateTime();
        return CourierDto.EligibilityResponse.builder()
                .courierId(profile.getId())
                .eligible(eligible)
                .reasonCode(reasonCode)
                .message(message)
                .evaluatedAt(evaluatedAt)
                .evaluatedDate(localDateTime.toLocalDate())
                .evaluatedLocalTime(localDateTime.toLocalTime())
                .withinSchedule(withinSchedule)
                .build();
    }

    private CourierDto.CourierProfileResponse toResponse(CourierProfile profile) {
        return CourierDto.CourierProfileResponse.builder()
                .id(profile.getId())
                .companyId(profile.getCompanyId())
                .courierType(profile.getCourierType())
                .employmentStatus(profile.getEmploymentStatus())
                .transportType(profile.getTransportType())
                .isVerified(profile.isVerified())
                .canTakeOrders(profile.isCanTakeOrders())
                .maxActiveOrders(profile.getMaxActiveOrders())
                .notes(profile.getNotes())
                .schedules(profile.getSchedules().stream()
                        .sorted(Comparator.comparing(CourierWorkSchedule::getWeekday)
                                .thenComparing(CourierWorkSchedule::getStartTime))
                        .map(schedule -> CourierDto.ScheduleResponse.builder()
                                .id(schedule.getId())
                                .weekday(schedule.getWeekday())
                                .startTime(schedule.getStartTime())
                                .endTime(schedule.getEndTime())
                                .timezone(schedule.getTimezone())
                                .active(schedule.isActive())
                                .build())
                        .toList())
                .documents(profile.getDocuments() == null ? List.of() : profile.getDocuments().stream()
                        .map(doc -> CourierDto.DocumentResponse.builder()
                                .id(doc.getId())
                                .documentType(doc.getDocumentType())
                                .documentNumber(doc.getDocumentNumber())
                                .fileUrl(doc.getFileUrl())
                                .status(doc.getStatus())
                                .rejectionReason(doc.getRejectionReason())
                                .submittedAt(doc.getSubmittedAt())
                                .build())
                        .toList())
                .missingDocumentTypes(getMissingDocumentTypes(profile))
                .createdAt(profile.getCreatedAt())
                .updatedAt(profile.getUpdatedAt())
                .build();
    }

    public CourierDto.CourierProfileResponse uploadDocument(UUID courierId, DocumentType type, String documentNumber, String fileUrl) {
        GatewayPrincipalProvider.GatewayPrincipal principal = gatewayPrincipalProvider.requireCurrentPrincipal();
        if (hasAnyRole(principal, "COURIER")) {
            requireSelf(courierId, "upload your document");
        }

        CourierProfile profile = findWithSchedules(courierId);
        CourierDocument doc = courierDocumentRepository.findByCourierIdAndDocumentType(courierId, type)
                .orElseGet(() -> CourierDocument.builder()
                        .id(UUID.randomUUID())
                        .courier(profile)
                        .documentType(type)
                        .build());
        doc.setDocumentNumber(documentNumber);
        doc.setFileUrl(fileUrl);
        doc.setStatus(DocumentStatus.PENDING);
        doc.setRejectionReason(null);
        courierDocumentRepository.save(doc);

        return get(courierId);
    }

    public CourierDto.CourierProfileResponse verifyDocument(UUID courierId, UUID documentId, CourierDto.VerifyDocumentRequest req) {
        GatewayPrincipalProvider.GatewayPrincipal principal = gatewayPrincipalProvider.requireCurrentPrincipal();
        CourierProfile profile = findWithSchedules(courierId);
        if (hasAnyRole(principal, "ADMIN", "SUPER_ADMIN")) {
            // OK
        } else if (hasAnyRole(principal, "DIRECTOR", "MANAGER")) {
            requireSameCompany(profile, requireCallerCompanyId(principal));
        } else {
            throw new BusinessException("FORBIDDEN", "Only privileged roles can verify documents");
        }

        CourierDocument doc = courierDocumentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException("DOCUMENT_NOT_FOUND", "Document not found"));

        if (!doc.getCourier().getId().equals(courierId)) {
            throw new BusinessException("INVALID_ARGUMENT", "Document does not belong to the courier");
        }

        doc.setStatus(req.status());
        if (req.status() == DocumentStatus.REJECTED) {
            doc.setRejectionReason(req.rejectionReason());
        } else {
            doc.setRejectionReason(null);
        }
        courierDocumentRepository.save(doc);

        reEvaluateVerification(profile);
        return toResponse(courierProfileRepository.save(profile));
    }

    private void reEvaluateVerification(CourierProfile profile) {
        List<DocumentType> requiredTypes = getRequiredDocumentTypes(profile.getTransportType());
        List<CourierDocument> docs = courierDocumentRepository.findByCourierId(profile.getId());
        boolean allApproved = requiredTypes.stream().allMatch(reqType -> docs != null && docs.stream()
                .anyMatch(d -> d.getDocumentType() == reqType && d.getStatus() == DocumentStatus.APPROVED));

        if (allApproved) {
            profile.setVerified(true);
            profile.setEmploymentStatus(EmploymentStatus.ACTIVE);
            profile.setCanTakeOrders(true);
        } else {
            profile.setVerified(false);
            profile.setEmploymentStatus(EmploymentStatus.ONBOARDING);
            profile.setCanTakeOrders(false);
        }
    }

    private List<DocumentType> getRequiredDocumentTypes(TransportType transportType) {
        List<DocumentType> types = new ArrayList<>();
        types.add(DocumentType.IDENTIFICATION);
        if (transportType == TransportType.CAR || transportType == TransportType.VAN) {
            types.add(DocumentType.DRIVERS_LICENSE);
        }
        return types;
    }

    private List<DocumentType> getMissingDocumentTypes(CourierProfile profile) {
        List<DocumentType> required = getRequiredDocumentTypes(profile.getTransportType());
        List<DocumentType> uploadedApprovedOrPending = profile.getDocuments() == null ? List.of() :
                profile.getDocuments().stream()
                        .filter(d -> d.getStatus() == DocumentStatus.APPROVED || d.getStatus() == DocumentStatus.PENDING)
                        .map(CourierDocument::getDocumentType)
                        .toList();
        return required.stream()
                .filter(r -> !uploadedApprovedOrPending.contains(r))
                .toList();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
