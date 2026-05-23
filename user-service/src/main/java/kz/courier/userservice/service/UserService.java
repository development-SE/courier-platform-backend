package kz.courier.userservice.service;

import kz.courier.userservice.dto.UserDto;
import kz.courier.userservice.model.Role;
import kz.courier.userservice.model.User;
import kz.courier.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private final UserRepository userRepo;

    /* ── CREATE ─────────────────────────────────────────────────────────── */
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public UserDto.Response create(UserDto.CreateRequest req) {
        if (userRepo.existsByEmail(req.getEmail()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "EMAIL_EXISTS: E-mail already taken");
        if (req.getPhone() != null && userRepo.existsByPhone(req.getPhone()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PHONE_EXISTS: Phone already taken");

        User user = User.builder()
                .firstName(req.getFirstName())
                .lastName(req.getLastName())
                .email(req.getEmail().toLowerCase())
                .phone(req.getPhone())
                .companyId(req.getCompanyId())
                .role(req.getRole())
                .active(true)
                .build();

        return toResponse(userRepo.save(user));
    }

    /* ── GET BY ID ──────────────────────────────────────────────────────── */
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    @Transactional(readOnly = true)
    public UserDto.Response getById(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public UserDto.Response getCurrentUser(String email, String userId, String role) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED: Missing authenticated email");
        }

        return userRepo.findByEmail(email.trim().toLowerCase())
                .map(this::toResponse)
                .orElseGet(() -> buildFallbackProfile(email, userId, role));
    }

    private UserDto.Response buildFallbackProfile(String email, String userId, String role) {
        String localPart = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        String firstName = localPart == null || localPart.isBlank() ? "Courier" : localPart;

        return UserDto.Response.builder()
                .id(parseUuid(userId))
                .firstName(firstName)
                .lastName("")
                .email(email)
                .phone(null)
                .companyId(null)
                .role(parseRole(role))
                .active(true)
                .build();
    }

    /* ── LIST + SEARCH + FILTER ─────────────────────────────────────────── */
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    @Transactional(readOnly = true)
    public UserDto.PageResponse list(int page, int size, String search, Role role, String sortBy, boolean asc) {
        var sort     = Sort.by(asc ? Sort.Direction.ASC : Sort.Direction.DESC, sortBy);
        var pageable = PageRequest.of(page - 1, size, sort);

        String effectiveSearch = search == null || search.isBlank() ? null : search.trim();
        Page<User> result;
        if (effectiveSearch != null) {
            result = userRepo.search(role, effectiveSearch, pageable);
        } else if (role != null) {
            result = userRepo.findByRole(role, pageable);
        } else {
            result = userRepo.findAll(pageable);
        }

        return UserDto.PageResponse.builder()
                .content(result.getContent().stream().map(this::toResponse).toList())
                .page(page)
                .pageSize(size)
                .totalItems(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .build();
    }

    /* ── UPDATE ─────────────────────────────────────────────────────────── */
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public UserDto.Response update(UUID id, UserDto.UpdateRequest req) {
        User user = findOrThrow(id);

        if (req.getEmail() != null) {
            if (userRepo.existsByEmailAndIdNot(req.getEmail(), id))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "EMAIL_EXISTS: E-mail already taken");
            user.setEmail(req.getEmail().toLowerCase());
        }
        if (req.getPhone() != null) {
            if (userRepo.existsByPhoneAndIdNot(req.getPhone(), id))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "PHONE_EXISTS: Phone already taken");
            user.setPhone(req.getPhone());
        }
        if (req.getFirstName()  != null) user.setFirstName(req.getFirstName());
        if (req.getLastName()   != null) user.setLastName(req.getLastName());
        if (req.getCompanyId()  != null) user.setCompanyId(req.getCompanyId());
        if (req.getRole()       != null) user.setRole(req.getRole());
        if (req.getActive()     != null) user.setActive(req.getActive());

        return toResponse(userRepo.save(user));
    }

    /* ── DELETE ─────────────────────────────────────────────────────────── */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public void delete(UUID id) {
        User user = findOrThrow(id);
        userRepo.delete(user);
    }

    /* ── HELPERS ────────────────────────────────────────────────────────── */
    private User findOrThrow(UUID id) {
        return userRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND: User not found"));
    }

    private UserDto.Response toResponse(User u) {
        return UserDto.Response.builder()
                .id(u.getId())
                .firstName(u.getFirstName())
                .lastName(u.getLastName())
                .email(u.getEmail())
                .phone(u.getPhone())
                .companyId(u.getCompanyId())
                .role(u.getRole())
                .active(u.isActive())
                .createdAt(u.getCreatedAt())
                .updatedAt(u.getUpdatedAt())
                .build();
    }

    private Role parseRole(String role) {
        if (role == null || role.isBlank()) {
            return Role.USER;
        }
        try {
            return Role.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return Role.USER;
        }
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
