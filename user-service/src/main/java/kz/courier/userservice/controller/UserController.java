package kz.courier.userservice.controller;

import jakarta.validation.Valid;
import kz.courier.userservice.dto.UserDto;
import kz.courier.userservice.model.Role;
import kz.courier.userservice.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * POST /users
     * Create a new user.
     * Roles: ADMIN, SUPER_ADMIN
     */
    @PostMapping
    public ResponseEntity<UserDto.Response> create(@Valid @RequestBody UserDto.CreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.create(req));
    }

    /**
     * GET /users/{id}
     * Get a single user by ID.
     * Roles: ADMIN, SUPER_ADMIN
     */
    @GetMapping("/{id}")
    public ResponseEntity<UserDto.Response> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.getById(id));
    }

    /**
     * GET /users?page=1&size=10&search=ali&role=COURIER&sortBy=createdAt&asc=false
     * List users with optional search and filters.
     * Roles: ADMIN, SUPER_ADMIN
     */
    @GetMapping
    public ResponseEntity<UserDto.PageResponse> list(
            @RequestParam(defaultValue = "1")           int page,
            @RequestParam(defaultValue = "10")          int size,
            @RequestParam(required = false)             String search,
            @RequestParam(required = false)             Role role,
            @RequestParam(defaultValue = "createdAt")   String sortBy,
            @RequestParam(defaultValue = "false")       boolean asc
    ) {
        return ResponseEntity.ok(userService.list(page, size, search, role, sortBy, asc));
    }

    /**
     * PUT /users/{id}
     * Update user fields (partial update — only non-null fields are changed).
     * Roles: ADMIN, SUPER_ADMIN
     */
    @PutMapping("/{id}")
    public ResponseEntity<UserDto.Response> update(
            @PathVariable UUID id,
            @Valid @RequestBody UserDto.UpdateRequest req) {
        return ResponseEntity.ok(userService.update(id, req));
    }

    /**
     * DELETE /users/{id}
     * Permanently delete a user.
     * Roles: SUPER_ADMIN only
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
