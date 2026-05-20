package kz.courier.courierservice.service;

import kz.courier.courierservice.dto.CourierDto;
import kz.courier.courierservice.entity.CourierProfile;
import kz.courier.courierservice.entity.CourierType;
import kz.courier.courierservice.entity.CourierWorkSchedule;
import kz.courier.courierservice.entity.EmploymentStatus;
import kz.courier.courierservice.entity.TransportType;
import kz.courier.courierservice.exception.BusinessException;
import kz.courier.courierservice.exception.CourierNotFoundException;
import kz.courier.courierservice.repository.CourierProfileRepository;
import kz.courier.courierservice.security.GatewayPrincipalProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class CourierService {

    private static final List<String> PRIVILEGED_ROLES =
            List.of("ADMIN", "SUPER_ADMIN", "MANAGER", "DIRECTOR");

    private final CourierProfileRepository courierProfileRepository;
    private final GatewayPrincipalProvider gatewayPrincipalProvider;

    public CourierDto.CourierProfileResponse create(CourierDto.CreateCourierRequest req) {

        boolean isPrivileged = gatewayPrincipalProvider.hasAnyRole("ADMIN", "SUPER_ADMIN");
        if (!isPrivileged) {
        UUID callerUserId = gatewayPrincipalProvider.requireCurrentUserId();
        if (!callerUserId.equals(req.userId())) {
            throw new BusinessException("FORBIDDEN",
                    "Couriers can only create their own profile");
        }
        // force contractor defaults — courier cannot choose type or self-verify
        req = new CourierDto.CreateCourierRequest(
                req.userId(),
                null,                        // no company
                CourierType.CONTRACTOR,      // always contractor
                EmploymentStatus.ONBOARDING, // always starts onboarding
                req.transportType(),         // courier chooses transport
                false,                       // isVerified = false
                false,                       // canTakeOrders = false
                1,                           // maxActiveOrders = 1
                req.notes(),
                null                         // no schedules
        );
    }

        if (courierProfileRepository.existsByUserId(req.userId())) {
            throw new BusinessException("COURIER_ALREADY_EXISTS",
                    "Courier profile already exists for user " + req.userId());
        }

        CourierProfile profile = CourierProfile.builder()
                .userId(req.userId())
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
        return toResponse(findWithSchedules(courierId));
    }

    @Transactional(readOnly = true)
    public Page<CourierDto.CourierProfileResponse> list(UUID companyId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return courierProfileRepository.findAllFiltered(companyId, pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public CourierDto.CourierProfileResponse getByUserId(UUID userId) {
        return toResponse(courierProfileRepository.findWithSchedulesByUserId(userId)
                .orElseThrow(() -> new CourierNotFoundException(userId)));
    }

    @Transactional(readOnly = true)
    public CourierDto.CourierProfileResponse getCurrent() {
        UUID currentUserId = gatewayPrincipalProvider.requireCurrentUserId();
        return courierProfileRepository.findWithSchedulesByUserId(currentUserId)
                .map(this::toResponse)
                .orElseGet(() -> buildSelfRegisteredContractor(currentUserId));
    }

    public CourierDto.CourierProfileResponse update(UUID courierId, CourierDto.UpdateCourierRequest req) {
        CourierProfile profile = findWithSchedules(courierId);

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

        return toResponse(courierProfileRepository.save(profile));
    }

    @Transactional(readOnly = true)
    public CourierDto.EligibilityResponse getEligibility(UUID courierId, OffsetDateTime at) {
        requirePrivilegedReader();

        CourierProfile profile = findWithSchedules(courierId);
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

    private void requirePrivilegedReader() {
        if (!gatewayPrincipalProvider.hasAnyRole(PRIVILEGED_ROLES.toArray(String[]::new))) {
            throw new BusinessException("FORBIDDEN",
                    "Only privileged roles may read courier eligibility");
        }
    }

    private CourierDto.CourierProfileResponse buildSelfRegisteredContractor(UUID userId) {
        if (!gatewayPrincipalProvider.hasAnyRole("COURIER")) {
            throw new CourierNotFoundException(userId);
        }

        return CourierDto.CourierProfileResponse.builder()
                .id(null)
                .userId(userId)
                .companyId(null)
                .courierType(CourierType.CONTRACTOR)
                .employmentStatus(EmploymentStatus.ONBOARDING)
                .transportType((TransportType) null)
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
                .userId(profile.getUserId())
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
                .userId(profile.getUserId())
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
                .createdAt(profile.getCreatedAt())
                .updatedAt(profile.getUpdatedAt())
                .build();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
