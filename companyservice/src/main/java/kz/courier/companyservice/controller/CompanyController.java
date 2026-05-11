package kz.courier.companyservice.controller;

import jakarta.validation.Valid;
import kz.courier.companyservice.dto.CompanyDto;
import kz.courier.companyservice.service.CompanyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<CompanyDto.Response> create(@Valid @RequestBody CompanyDto.CreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(companyService.create(req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','DIRECTOR','MANAGER')")
    public ResponseEntity<CompanyDto.Response> getById(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Company-Id", required = false) String companyIdHeader,
            @RequestHeader(value = "X-User-Roles", defaultValue = "") String role) {

        return ResponseEntity.ok(companyService.getById(id, parseCompanyId(companyIdHeader), role));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','DIRECTOR','MANAGER')")
    public ResponseEntity<CompanyDto.PageResponse> list(
            @RequestParam(defaultValue = "1")  int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false)    String search,
            @RequestHeader(value = "X-Company-Id", required = false) String companyIdHeader,
            @RequestHeader(value = "X-User-Roles", defaultValue = "") String role) {

        return ResponseEntity.ok(companyService.list(page, size, search, parseCompanyId(companyIdHeader), role));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','DIRECTOR')")
    public ResponseEntity<CompanyDto.Response> update(
            @PathVariable UUID id,
            @Valid @RequestBody CompanyDto.UpdateRequest req) {
        return ResponseEntity.ok(companyService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        companyService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private UUID parseCompanyId(String header) {
        if (header == null || header.isBlank()) return null;
        try { return UUID.fromString(header); } catch (Exception e) { return null; }
    }
}
