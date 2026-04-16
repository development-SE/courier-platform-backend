package kz.courier.companyservice.controller;

import jakarta.validation.Valid;
import kz.courier.companyservice.dto.EmployeeDto;
import kz.courier.companyservice.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;

    /**
     * POST /employees
     * - DIRECTOR: companyId auto-assigned from X-Company-Id header (JWT claim)
     * - ADMIN/SUPER_ADMIN: must pass companyId as query param
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','DIRECTOR')")
    public ResponseEntity<EmployeeDto.Response> create(
            @Valid @RequestBody EmployeeDto.CreateRequest req,
            @RequestHeader(value = "X-Company-Id", required = false) String companyIdHeader,
            @RequestParam(required = false) UUID companyId,
            @RequestHeader(value = "X-User-Roles", defaultValue = "") String role) {

        UUID resolvedCompanyId = resolveCompanyId(companyIdHeader, companyId, role);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(employeeService.create(req, resolvedCompanyId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','DIRECTOR')")
    public ResponseEntity<EmployeeDto.Response> getById(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Company-Id", required = false) String companyIdHeader,
            @RequestHeader(value = "X-User-Roles", defaultValue = "") String role) {

        UUID callerCompanyId = parseCompanyId(companyIdHeader);
        return ResponseEntity.ok(employeeService.getById(id, callerCompanyId, role));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','DIRECTOR')")
    public ResponseEntity<EmployeeDto.PageResponse> list(
            @RequestParam(defaultValue = "1")  int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false)    String search,
            @RequestParam(required = false)    String role,
            @RequestParam(required = false)    UUID companyId,
            @RequestHeader(value = "X-Company-Id", required = false) String companyIdHeader,
            @RequestHeader(value = "X-User-Roles", defaultValue = "") String callerRole) {

        UUID callerCompanyId = parseCompanyId(companyIdHeader);
        return ResponseEntity.ok(
                employeeService.list(page, size, search, role, companyId, callerCompanyId, callerRole));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','DIRECTOR')")
    public ResponseEntity<EmployeeDto.Response> update(
            @PathVariable UUID id,
            @Valid @RequestBody EmployeeDto.UpdateRequest req,
            @RequestHeader(value = "X-Company-Id", required = false) String companyIdHeader,
            @RequestHeader(value = "X-User-Roles", defaultValue = "") String role) {

        UUID callerCompanyId = parseCompanyId(companyIdHeader);
        return ResponseEntity.ok(employeeService.update(id, req, callerCompanyId, role));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DIRECTOR')")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Company-Id", required = false) String companyIdHeader,
            @RequestHeader(value = "X-User-Roles", defaultValue = "") String role) {

        UUID callerCompanyId = parseCompanyId(companyIdHeader);
        employeeService.delete(id, callerCompanyId, role);
        return ResponseEntity.noContent().build();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID resolveCompanyId(String header, UUID queryParam, String role) {
        if ("DIRECTOR".equals(role)) {
            if (header == null || header.isBlank())
                throw new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "X-Company-Id header missing for DIRECTOR");
            return UUID.fromString(header);
        }
        if (queryParam == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "companyId query param required for ADMIN/SUPER_ADMIN");
        return queryParam;
    }

    private UUID parseCompanyId(String header) {
        if (header == null || header.isBlank()) return null;
        try { return UUID.fromString(header); } catch (Exception e) { return null; }
    }
}
