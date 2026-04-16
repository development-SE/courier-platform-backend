package kz.courier.companyservice.controller;

import jakarta.validation.Valid;
import kz.courier.companyservice.dto.AddressDto;
import kz.courier.companyservice.service.AddressService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    /**
     * POST /addresses
     * DIRECTOR: companyId auto-assigned from X-Company-Id (JWT claim); body companyId is ignored.
     * ADMIN/SUPER_ADMIN: companyId must be in request body.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','DIRECTOR','MANAGER')")
    public ResponseEntity<AddressDto.Response> create(
            @Valid @RequestBody AddressDto.CreateRequest req,
            @RequestHeader(value = "X-Company-Id", required = false) String companyIdHeader,
            @RequestHeader(value = "X-User-Roles",  defaultValue = "") String role) {

        UUID resolvedCompanyId = resolveCompanyId(companyIdHeader, req.getCompanyId(), role);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(addressService.create(req, resolvedCompanyId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','DIRECTOR','MANAGER')")
    public ResponseEntity<AddressDto.Response> getById(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Company-Id", required = false) String companyIdHeader,
            @RequestHeader(value = "X-User-Roles",  defaultValue = "") String role) {

        UUID callerCompanyId = isCompanyScopedRole(role) ? requireCompanyId(companyIdHeader) : null;
        return ResponseEntity.ok(addressService.getById(id, callerCompanyId));
    }

    /**
     * GET /addresses
     * DIRECTOR/MANAGER: see only their company's addresses (from X-Company-Id).
     * ADMIN/SUPER_ADMIN: see all, optionally filtered by companyId query param.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','DIRECTOR','MANAGER')")
    public ResponseEntity<AddressDto.PageResponse> list(
            @RequestParam(defaultValue = "1")  int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false)    UUID companyId,
            @RequestHeader(value = "X-Company-Id", required = false) String companyIdHeader,
            @RequestHeader(value = "X-User-Roles",  defaultValue = "") String role) {

        UUID effectiveCompanyId = isCompanyScopedRole(role)
                ? requireCompanyId(companyIdHeader)
                : companyId;

        return ResponseEntity.ok(addressService.list(page, size, effectiveCompanyId));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','DIRECTOR','MANAGER')")
    public ResponseEntity<AddressDto.Response> update(
            @PathVariable UUID id,
            @Valid @RequestBody AddressDto.UpdateRequest req,
            @RequestHeader(value = "X-Company-Id", required = false) String companyIdHeader,
            @RequestHeader(value = "X-User-Roles",  defaultValue = "") String role) {

        UUID callerCompanyId = isCompanyScopedRole(role) ? requireCompanyId(companyIdHeader) : null;
        return ResponseEntity.ok(addressService.update(id, req, callerCompanyId));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DIRECTOR','MANAGER')")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Company-Id", required = false) String companyIdHeader,
            @RequestHeader(value = "X-User-Roles",  defaultValue = "") String role) {

        UUID callerCompanyId = isCompanyScopedRole(role) ? requireCompanyId(companyIdHeader) : null;
        addressService.delete(id, callerCompanyId);
        return ResponseEntity.noContent().build();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Returns true for roles that are scoped to a single company. */
    private boolean isCompanyScopedRole(String role) {
        return "DIRECTOR".equals(role) || "PARTNER".equals(role) || "MANAGER".equals(role);
    }

    private UUID resolveCompanyId(String header, UUID bodyCompanyId, String role) {
        if (isCompanyScopedRole(role)) {
            UUID fromHeader = parseCompanyId(header);
            if (fromHeader == null)
                throw new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "X-Company-Id header missing");
            return fromHeader;
        }
        if (bodyCompanyId == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "companyId is required in request body");
        return bodyCompanyId;
    }

    private UUID parseCompanyId(String header) {
        if (header == null || header.isBlank()) return null;
        try { return UUID.fromString(header); } catch (Exception e) { return null; }
    }

    private UUID requireCompanyId(String header) {
        UUID companyId = parseCompanyId(header);
        if (companyId == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "X-Company-Id header missing");
        }
        return companyId;
    }
}
