package kz.courier.logisticsservice.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import kz.courier.logisticsservice.entity.AssignmentStatus;
import kz.courier.logisticsservice.entity.RouteStatus;
import kz.courier.logisticsservice.repository.AssignmentRepository;
import kz.courier.logisticsservice.repository.CourierRouteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class AssignmentMetrics {

    private static final List<AssignmentStatus> TERMINAL_ASSIGNMENT_STATUSES =
            List.of(AssignmentStatus.DELIVERED, AssignmentStatus.CANCELLED, AssignmentStatus.FAILED,
                    AssignmentStatus.REJECTED, AssignmentStatus.TIMED_OUT, AssignmentStatus.MANUAL_REQUIRED);

    private final MeterRegistry registry;
    private final AssignmentRepository assignmentRepository;
    private final CourierRouteRepository routeRepository;

    @jakarta.annotation.PostConstruct
    void registerGauges() {
        registry.gauge("courier_pending_offers", assignmentRepository,
                repository -> repository.countByAssignmentStatus(AssignmentStatus.PENDING));
        registry.gauge("courier_manual_required_assignments", assignmentRepository,
                repository -> repository.countByAssignmentStatus(AssignmentStatus.MANUAL_REQUIRED));
        registry.gauge("courier_active_assignments", assignmentRepository,
                repository -> repository.countByAssignmentStatusNotIn(TERMINAL_ASSIGNMENT_STATUSES));
        registry.gauge("courier_active_routes", routeRepository,
                repository -> repository.countByStatus(RouteStatus.ACTIVE));
    }

    Timer.Sample startAssignmentTimer() {
        return Timer.start(registry);
    }

    void recordDuration(Timer.Sample sample, String serviceType, String result) {
        if (sample == null) {
            return;
        }
        sample.stop(Timer.builder("courier_assignment_duration_seconds")
                .tag("serviceType", safeTag(serviceType))
                .tag("result", safeTag(result))
                .publishPercentileHistogram()
                .register(registry));
    }

    void recordAttempt(String serviceType, String assignmentPolicy) {
        counter("courier_assignment_attempts_total",
                "serviceType", serviceType,
                "assignmentPolicy", assignmentPolicy).increment();
    }

    void recordSuccess(String serviceType, String assignmentPolicy) {
        counter("courier_assignment_success_total",
                "serviceType", serviceType,
                "assignmentPolicy", assignmentPolicy).increment();
    }

    void recordFailure(String serviceType, String reason) {
        counter("courier_assignment_failed_total",
                "serviceType", serviceType,
                "reason", reason).increment();
    }

    void recordRejected(String reason) {
        counter("courier_assignment_rejected_total", "reason", reason).increment();
    }

    void recordTimedOut(String reason) {
        counter("courier_assignment_timed_out_total", "reason", reason).increment();
    }

    void recordManualRequired(String reason) {
        counter("courier_assignment_manual_required_total", "reason", reason).increment();
    }

    void recordDuplicatePrevented(String reason) {
        counter("courier_assignment_duplicate_prevented_total", "reason", reason).increment();
    }

    void recordCleanup(String reason) {
        counter("courier_assignment_cleanup_total", "reason", reason).increment();
    }

    void recordCandidatesFound(int count) {
        summary("courier_assignment_candidates_found").record(Math.max(0, count));
    }

    void recordCandidatesEligible(int count) {
        summary("courier_assignment_candidates_eligible").record(Math.max(0, count));
    }

    private Counter counter(String name, String... tags) {
        String[] sanitized = new String[tags.length];
        for (int i = 0; i < tags.length; i += 2) {
            sanitized[i] = tags[i];
            sanitized[i + 1] = safeTag(tags[i + 1]);
        }
        return Counter.builder(name).tags(sanitized).register(registry);
    }

    private DistributionSummary summary(String name) {
        return DistributionSummary.builder(name)
                .baseUnit("couriers")
                .publishPercentileHistogram()
                .register(registry);
    }

    private String safeTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "_");
    }
}
