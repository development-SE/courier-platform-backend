package kz.courier.userservice.controller;

import jakarta.validation.Valid;
import kz.courier.userservice.dto.UserAddressDto;
import kz.courier.userservice.service.UserAddressService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserAddressController {

    private final UserAddressService addressService;

    @PostMapping("/me/addresses")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserAddressDto.Response> createMyAddress(
            Authentication authentication,
            @Valid @RequestBody UserAddressDto.CreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(addressService.create(currentUserId(authentication), req));
    }

    @GetMapping("/me/addresses")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserAddressDto.PageResponse> listMyAddresses(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(addressService.list(currentUserId(authentication), page, size));
    }

    @GetMapping("/me/addresses/{addressId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserAddressDto.Response> getMyAddress(
            Authentication authentication,
            @PathVariable UUID addressId) {
        return ResponseEntity.ok(addressService.getById(currentUserId(authentication), addressId));
    }

    @PutMapping("/me/addresses/{addressId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserAddressDto.Response> updateMyAddress(
            Authentication authentication,
            @PathVariable UUID addressId,
            @Valid @RequestBody UserAddressDto.UpdateRequest req) {
        return ResponseEntity.ok(addressService.update(currentUserId(authentication), addressId, req));
    }

    @PatchMapping("/me/addresses/{addressId}/default")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserAddressDto.Response> setMyDefaultAddress(
            Authentication authentication,
            @PathVariable UUID addressId) {
        return ResponseEntity.ok(addressService.setDefault(currentUserId(authentication), addressId));
    }

    @DeleteMapping("/me/addresses/{addressId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteMyAddress(
            Authentication authentication,
            @PathVariable UUID addressId) {
        addressService.delete(currentUserId(authentication), addressId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{userId}/addresses")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<UserAddressDto.PageResponse> listUserAddresses(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(addressService.list(userId, page, size));
    }

    @GetMapping("/addresses")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<UserAddressDto.PageResponse> listAllUserAddresses(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(addressService.listAll(page, size));
    }

    private UUID currentUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED: Missing authenticated user");
        }
        try {
            return UUID.fromString(authentication.getPrincipal().toString());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED: Invalid authenticated user id");
        }
    }
}
