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

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','DIRECTOR')")
    public ResponseEntity<AddressDto.Response> create(@Valid @RequestBody AddressDto.CreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(addressService.create(req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','DIRECTOR')")
    public ResponseEntity<AddressDto.Response> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(addressService.getById(id));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','DIRECTOR')")
    public ResponseEntity<AddressDto.PageResponse> list(
            @RequestParam(defaultValue = "1")  int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false)    UUID companyId) {
        return ResponseEntity.ok(addressService.list(page, size, companyId));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','DIRECTOR')")
    public ResponseEntity<AddressDto.Response> update(
            @PathVariable UUID id,
            @Valid @RequestBody AddressDto.UpdateRequest req) {
        return ResponseEntity.ok(addressService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DIRECTOR')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        addressService.delete(id);
        return ResponseEntity.noContent().build();
    }
}