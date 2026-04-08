package kz.courier.logisticsservice.controller;

import jakarta.validation.Valid;
import kz.courier.logisticsservice.dto.LogisticsDto;
import kz.courier.logisticsservice.entity.AssignmentStatus;
import kz.courier.logisticsservice.service.LogisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST API for courier assignment lifecycle management.
 *
 * <p>All endpoints expect the API Gateway to inject {@code X-User-Id},
 * {@code X-User-Roles}, and {@code X-Company-Id} headers after JWT validation.
 *
 * <pre>
 * POST   /assignments                      — create a new assignment
 * GET    /assignments/{id}                 — get assignment by ID
 * GET    /assignments                      — list/filter assignments (paginated)
 * PATCH  /assignments/{id}/status          — transition assignment status
 * GET    /assignments/{id}/history         — full audit trail
 * </pre>
 */
@RestController
@RequestMapping("/assignments")
@RequiredArgsConstructor
public class AssignmentController {

    private final LogisticsService service;

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Creates a new courier assignment for an order.
     * Returns 409 if an active (non-terminal) assignment already exists for that order.
     */
    @PostMapping
    public ResponseEntity<LogisticsDto.ApiResponse<LogisticsDto.AssignmentResponse>> create(
            @Valid @RequestBody LogisticsDto.CreateAssignmentRequest req) {

        LogisticsDto.AssignmentResponse body = service.createAssignment(req);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(LogisticsDto.ApiResponse.ok(body));
    }

    /**
     * Automatically selects the best currently available courier for the order.
     */
    @PostMapping("/auto/{orderId}")
    public ResponseEntity<LogisticsDto.ApiResponse<LogisticsDto.AutoAssignResponse>> autoAssign(
            @PathVariable UUID orderId) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(LogisticsDto.ApiResponse.ok(service.autoAssign(orderId)));
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<LogisticsDto.ApiResponse<LogisticsDto.AssignmentResponse>> getById(
            @PathVariable UUID id) {

        return ResponseEntity.ok(LogisticsDto.ApiResponse.ok(service.getAssignment(id)));
    }

    /**
     * Paginated list with optional filters for courier, order, or status.
     *
     * <p>Query params:
     * <ul>
     *   <li>{@code courierId} – filter by courier UUID</li>
     *   <li>{@code orderId}   – filter by order UUID</li>
     *   <li>{@code status}    – filter by {@link AssignmentStatus} name</li>
     *   <li>{@code page}      – 1-based page index (default 1)</li>
     *   <li>{@code pageSize}  – items per page (default 20)</li>
     *   <li>{@code sortBy}    – field name (default "assignedAt")</li>
     *   <li>{@code desc}      – descending sort (default true)</li>
     * </ul>
     */
    @GetMapping
    public ResponseEntity<LogisticsDto.ApiResponse<LogisticsDto.PagedAssignments>> list(
            @RequestParam(required = false)             UUID             courierId,
            @RequestParam(required = false)             UUID             orderId,
            @RequestParam(required = false)             AssignmentStatus status,
            @RequestParam(defaultValue = "1")           int              page,
            @RequestParam(defaultValue = "20")          int              pageSize,
            @RequestParam(defaultValue = "assignedAt")  String           sortBy,
            @RequestParam(defaultValue = "true")        boolean          desc) {

        return ResponseEntity.ok(
                LogisticsDto.ApiResponse.ok(
                        service.listAssignments(courierId, orderId, status, page, pageSize, sortBy, desc)));
    }

    // ── Status transition ─────────────────────────────────────────────────────

    /**
     * Transitions the assignment to a new status.
     * Returns 422 if the transition is not allowed from the current status,
     * or if the assignment is already in a terminal state.
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<LogisticsDto.ApiResponse<LogisticsDto.AssignmentResponse>> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody LogisticsDto.UpdateStatusRequest req) {

        return ResponseEntity.ok(
                LogisticsDto.ApiResponse.ok(service.updateStatus(id, req)));
    }

    // ── History ───────────────────────────────────────────────────────────────

    @GetMapping("/{id}/history")
    public ResponseEntity<LogisticsDto.ApiResponse<List<LogisticsDto.HistoryEntry>>> history(
            @PathVariable UUID id) {

        return ResponseEntity.ok(LogisticsDto.ApiResponse.ok(service.getHistory(id)));
    }
}
