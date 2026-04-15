package kz.courier.companyservice.service;

import kz.courier.companyservice.dto.EmployeeDto;
import kz.courier.companyservice.grpc.AuthGrpcClient;
import kz.courier.companyservice.model.Employee;
import kz.courier.companyservice.repository.CompanyRepository;
import kz.courier.companyservice.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class EmployeeService {

    private final EmployeeRepository employeeRepo;
    private final CompanyRepository companyRepo;
    private final AuthGrpcClient authGrpcClient;

    /**
     * Create employee:
     * 1. Validate role (only DIRECTOR and MANAGER are allowed for employees)
     * 2. Verify company exists
     * 3. Register user in auth-service with the requested role
     * 4. Store employee locally with returned authUserId
     *
     * @param req        employee data (role defaults to MANAGER when absent)
     * @param companyId  from JWT claim (X-Company-Id header) for DIRECTOR,
     *                   or from request for ADMIN/SUPER_ADMIN
     */
    public EmployeeDto.Response create(EmployeeDto.CreateRequest req, UUID companyId) {
        // Resolve and validate role
        String role = (req.getRole() == null || req.getRole().isBlank()) ? "MANAGER" : req.getRole().toUpperCase();
        if (!role.equals("DIRECTOR") && !role.equals("MANAGER"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "INVALID_ROLE: Only DIRECTOR and MANAGER are allowed for employees. Got: " + role);

        // Verify company exists
        if (!companyRepo.existsById(companyId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "COMPANY_NOT_FOUND");

        if (employeeRepo.existsByEmail(req.getEmail()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "EMAIL_EXISTS: Email already taken");

        // Register in auth-service with the requested role
        String authUserId = authGrpcClient.registerUser(
                req.getEmail(),
                req.getPassword(),
                req.getFirstName(),
                req.getLastName(),
                req.getPhone(),
                role
        );

        Employee employee = Employee.builder()
                .firstName(req.getFirstName())
                .lastName(req.getLastName())
                .email(req.getEmail().toLowerCase())
                .phone(req.getPhone())
                .companyId(companyId)
                .authUserId(UUID.fromString(authUserId))
                .role(role)
                .build();

        return toResponse(employeeRepo.save(employee));
    }

    @Transactional(readOnly = true)
    public EmployeeDto.Response getById(UUID id, UUID callerCompanyId, String callerRole) {
        Employee employee = findOrThrow(id);
        enforceCompanyAccess(employee.getCompanyId(), callerCompanyId, callerRole);
        return toResponse(employee);
    }

    @Transactional(readOnly = true)
    public EmployeeDto.PageResponse list(int page, int size, String search,
                                          UUID filterCompanyId, UUID callerCompanyId, String callerRole) {
        // DIRECTOR can only see their own company's employees
        UUID effectiveCompanyId = isDirector(callerRole) ? callerCompanyId : filterCompanyId;

        var pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Employee> result = (search != null && !search.isBlank() || effectiveCompanyId != null)
                ? employeeRepo.search(effectiveCompanyId, search, pageable)
                : employeeRepo.findAll(pageable);

        return EmployeeDto.PageResponse.builder()
                .content(result.getContent().stream().map(this::toResponse).toList())
                .page(page)
                .pageSize(size)
                .totalItems(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .build();
    }

    public EmployeeDto.Response update(UUID id, EmployeeDto.UpdateRequest req,
                                        UUID callerCompanyId, String callerRole) {
        Employee employee = findOrThrow(id);
        enforceCompanyAccess(employee.getCompanyId(), callerCompanyId, callerRole);

        if (req.getEmail() != null) {
            if (employeeRepo.existsByEmailAndIdNot(req.getEmail(), id))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "EMAIL_EXISTS");
            employee.setEmail(req.getEmail().toLowerCase());
        }
        if (req.getFirstName() != null) employee.setFirstName(req.getFirstName());
        if (req.getLastName()  != null) employee.setLastName(req.getLastName());
        if (req.getPhone()     != null) employee.setPhone(req.getPhone());

        return toResponse(employeeRepo.save(employee));
    }

    public void delete(UUID id, UUID callerCompanyId, String callerRole) {
        Employee employee = findOrThrow(id);
        enforceCompanyAccess(employee.getCompanyId(), callerCompanyId, callerRole);
        employeeRepo.delete(employee);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void enforceCompanyAccess(UUID employeeCompanyId, UUID callerCompanyId, String callerRole) {
        if (isDirector(callerRole) && !employeeCompanyId.equals(callerCompanyId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCESS_DENIED: Not your company");
    }

    private boolean isDirector(String role) {
        return "DIRECTOR".equals(role);
    }

    private Employee findOrThrow(UUID id) {
        return employeeRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "EMPLOYEE_NOT_FOUND"));
    }

    private EmployeeDto.Response toResponse(Employee e) {
        return EmployeeDto.Response.builder()
                .id(e.getId())
                .firstName(e.getFirstName())
                .lastName(e.getLastName())
                .email(e.getEmail())
                .phone(e.getPhone())
                .companyId(e.getCompanyId())
                .authUserId(e.getAuthUserId())
                .role(e.getRole())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}