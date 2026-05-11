package kz.courier.companyservice.service;

import kz.courier.companyservice.dto.CompanyDto;
import kz.courier.companyservice.model.Company;
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
public class CompanyService {

    private final CompanyRepository companyRepo;
    private final EmployeeRepository employeeRepo;

    public CompanyDto.Response create(CompanyDto.CreateRequest req) {
        String normalizedName = normalizeName(req.getName());
        if (companyRepo.existsByNormalizedName(normalizedName))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "NAME_EXISTS: Company name already registered");

        if (companyRepo.existsByBin(req.getBin()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "BIN_EXISTS: BIN already registered");

        Company company = Company.builder()
                .name(normalizedName)
                .bin(req.getBin())
                .build();

        return toResponse(companyRepo.save(company));
    }

    @Transactional(readOnly = true)
    public CompanyDto.Response getById(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public CompanyDto.Response getById(UUID id, UUID callerCompanyId, String callerRole) {
        enforceCompanyAccess(id, callerCompanyId, callerRole);
        return toResponse(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public CompanyDto.PageResponse list(int page, int size, String search) {
        var pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Company> result = (search != null && !search.isBlank())
                ? companyRepo.search(search, pageable)
                : companyRepo.findAll(pageable);

        return toPageResponse(result, page, size);
    }

    @Transactional(readOnly = true)
    public CompanyDto.PageResponse list(int page, int size, String search, UUID callerCompanyId, String callerRole) {
        if (isCompanyScopedRole(callerRole)) {
            if (callerCompanyId == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "COMPANY_SCOPE_MISSING");
            }

            Company company = findOrThrow(callerCompanyId);
            return CompanyDto.PageResponse.builder()
                    .content(java.util.List.of(toResponse(company)))
                    .page(page)
                    .pageSize(size)
                    .totalItems(1)
                    .totalPages(1)
                    .build();
        }

        return list(page, size, search);
    }

    private CompanyDto.PageResponse toPageResponse(Page<Company> result, int page, int size) {
        return CompanyDto.PageResponse.builder()
                .content(result.getContent().stream().map(this::toResponse).toList())
                .page(page)
                .pageSize(size)
                .totalItems(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .build();
    }

    public CompanyDto.Response update(UUID id, CompanyDto.UpdateRequest req) {
        Company company = findOrThrow(id);

        if (req.getBin() != null) {
            if (companyRepo.existsByBinAndIdNot(req.getBin(), id))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "BIN_EXISTS: BIN already registered");
            company.setBin(req.getBin());
        }
        if (req.getName() != null) {
            String normalizedName = normalizeName(req.getName());
            if (companyRepo.existsByNormalizedNameAndIdNot(normalizedName, id))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "NAME_EXISTS: Company name already registered");
            company.setName(normalizedName);
        }

        return toResponse(companyRepo.save(company));
    }

    public void delete(UUID id) {
        companyRepo.delete(findOrThrow(id));
    }

    private Company findOrThrow(UUID id) {
        return companyRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "COMPANY_NOT_FOUND"));
    }

    private void enforceCompanyAccess(UUID targetCompanyId, UUID callerCompanyId, String callerRole) {
        if (isCompanyScopedRole(callerRole) && !targetCompanyId.equals(callerCompanyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCESS_DENIED: Not your company");
        }
    }

    private boolean isCompanyScopedRole(String role) {
        return "DIRECTOR".equals(role) || "MANAGER".equals(role);
    }

    private String normalizeName(String name) {
        return name == null ? null : name.trim().replaceAll("\\s+", " ");
    }

    private CompanyDto.Response toResponse(Company c) {
        Employee director = employeeRepo
                .findFirstByCompanyIdAndRole(c.getId(), "DIRECTOR")
                .orElse(null);
        String directorName = director == null
                ? null
                : (director.getFirstName() + " " + director.getLastName()).trim();

        return CompanyDto.Response.builder()
                .id(c.getId())
                .name(c.getName())
                .bin(c.getBin())
                .directorId(director != null ? director.getId() : null)
                .director(directorName)
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}
