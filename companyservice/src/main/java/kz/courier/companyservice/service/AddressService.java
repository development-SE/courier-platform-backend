package kz.courier.companyservice.service;

import kz.courier.companyservice.dto.AddressDto;
import kz.courier.companyservice.model.Address;
import kz.courier.companyservice.repository.AddressRepository;
import kz.courier.companyservice.repository.CompanyRepository;
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
public class AddressService {

    private final AddressRepository addressRepo;
    private final CompanyRepository companyRepo;

    /**
     * Create an address.
     *
     * @param req             address fields (companyId may be null for DIRECTOR)
     * @param resolvedCompanyId effective companyId resolved by the controller
     *                          (from X-Company-Id for DIRECTOR, from body for ADMIN)
     */
    public AddressDto.Response create(AddressDto.CreateRequest req, UUID resolvedCompanyId) {
        if (!companyRepo.existsById(resolvedCompanyId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "COMPANY_NOT_FOUND");

        Address address = Address.builder()
                .companyId(resolvedCompanyId)
                .street(req.getStreet())
                .house(req.getHouse())
                .apartment(req.getApartment())
                .entrance(req.getEntrance())
                .build();

        return toResponse(addressRepo.save(address));
    }

    @Transactional(readOnly = true)
    public AddressDto.Response getById(UUID id, UUID callerCompanyId) {
        Address address = findOrThrow(id);
        enforceOwnership(address, callerCompanyId);
        return toResponse(address);
    }

    @Transactional(readOnly = true)
    public AddressDto.PageResponse list(int page, int size, UUID companyId) {
        var pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Address> result = companyId != null
                ? addressRepo.findByCompanyId(companyId, pageable)
                : addressRepo.findAll(pageable);

        return AddressDto.PageResponse.builder()
                .content(result.getContent().stream().map(this::toResponse).toList())
                .page(page)
                .pageSize(size)
                .totalItems(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .build();
    }

    /**
     * Update an address.
     *
     * @param callerCompanyId non-null for DIRECTOR/MANAGER — enforces ownership check.
     *                        Null for ADMIN/SUPER_ADMIN (no restriction).
     */
    public AddressDto.Response update(UUID id, AddressDto.UpdateRequest req, UUID callerCompanyId) {
        Address address = findOrThrow(id);
        enforceOwnership(address, callerCompanyId);

        if (req.getStreet()    != null) address.setStreet(req.getStreet());
        if (req.getHouse()     != null) address.setHouse(req.getHouse());
        if (req.getApartment() != null) address.setApartment(req.getApartment());
        if (req.getEntrance()  != null) address.setEntrance(req.getEntrance());

        return toResponse(addressRepo.save(address));
    }

    /**
     * Delete an address.
     *
     * @param callerCompanyId non-null for DIRECTOR/MANAGER — enforces ownership check.
     */
    public void delete(UUID id, UUID callerCompanyId) {
        Address address = findOrThrow(id);
        enforceOwnership(address, callerCompanyId);
        addressRepo.delete(address);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Throws 403 if callerCompanyId is set and does not match the address's company. */
    private void enforceOwnership(Address address, UUID callerCompanyId) {
        if (callerCompanyId != null && !callerCompanyId.equals(address.getCompanyId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED: Address does not belong to your company");
    }

    private Address findOrThrow(UUID id) {
        return addressRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ADDRESS_NOT_FOUND"));
    }

    private AddressDto.Response toResponse(Address a) {
        return AddressDto.Response.builder()
                .id(a.getId())
                .companyId(a.getCompanyId())
                .street(a.getStreet())
                .house(a.getHouse())
                .apartment(a.getApartment())
                .entrance(a.getEntrance())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }
}
