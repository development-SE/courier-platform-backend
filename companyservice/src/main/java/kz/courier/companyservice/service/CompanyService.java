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
        if (companyRepo.existsByBin(req.getBin()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "BIN_EXISTS: BIN already registered");

        Company company = Company.builder()
                .name(req.getName())
                .bin(req.getBin())
                .build();

        return toResponse(companyRepo.save(company));
    }

    @Transactional(readOnly = true)
    public CompanyDto.Response getById(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public CompanyDto.PageResponse list(int page, int size, String search) {
        var pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Company> result = (search != null && !search.isBlank())
                ? companyRepo.search(search, pageable)
                : companyRepo.findAll(pageable);

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
        if (req.getName() != null) company.setName(req.getName());

        return toResponse(companyRepo.save(company));
    }

    public void delete(UUID id) {
        companyRepo.delete(findOrThrow(id));
    }

    private Company findOrThrow(UUID id) {
        return companyRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "COMPANY_NOT_FOUND"));
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