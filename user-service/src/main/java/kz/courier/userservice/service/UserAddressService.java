package kz.courier.userservice.service;

import kz.courier.userservice.dto.UserAddressDto;
import kz.courier.userservice.model.UserAddress;
import kz.courier.userservice.repository.UserAddressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class UserAddressService {

    private final UserAddressRepository addressRepo;

    public UserAddressDto.Response create(UUID userId, UserAddressDto.CreateRequest req) {
        requireUserId(userId);

        boolean makeDefault = Boolean.TRUE.equals(req.getDefaultAddress())
                || !addressRepo.existsByUserId(userId);
        if (makeDefault) {
            addressRepo.clearDefaultForUser(userId);
        }

        UserAddress address = UserAddress.builder()
                .userId(userId)
                .label(blankToNull(req.getLabel()))
                .city(req.getCity())
                .street(req.getStreet())
                .house(req.getHouse())
                .apartment(blankToNull(req.getApartment()))
                .entrance(blankToNull(req.getEntrance()))
                .floor(blankToNull(req.getFloor()))
                .latitude(req.getLatitude())
                .longitude(req.getLongitude())
                .defaultAddress(makeDefault)
                .build();

        return toResponse(addressRepo.save(address));
    }

    @Transactional(readOnly = true)
    public UserAddressDto.Response getById(UUID userId, UUID addressId) {
        return toResponse(findOwnedAddress(userId, addressId));
    }

    @Transactional(readOnly = true)
    public UserAddressDto.PageResponse list(UUID userId, int page, int size) {
        requireUserId(userId);

        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        var pageable = PageRequest.of(
                safePage - 1,
                safeSize,
                Sort.by(Sort.Direction.DESC, "defaultAddress")
                        .and(Sort.by(Sort.Direction.DESC, "createdAt")));

        Page<UserAddress> result = addressRepo.findByUserId(userId, pageable);
        return UserAddressDto.PageResponse.builder()
                .content(result.getContent().stream().map(this::toResponse).toList())
                .page(safePage)
                .pageSize(safeSize)
                .totalItems(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .build();
    }

    @Transactional(readOnly = true)
    public UserAddressDto.PageResponse listAll(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        var pageable = PageRequest.of(
                safePage - 1,
                safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<UserAddress> result = addressRepo.findAll(pageable);
        return UserAddressDto.PageResponse.builder()
                .content(result.getContent().stream().map(this::toResponse).toList())
                .page(safePage)
                .pageSize(safeSize)
                .totalItems(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .build();
    }

    public UserAddressDto.Response update(UUID userId, UUID addressId, UserAddressDto.UpdateRequest req) {
        UserAddress address = findOwnedAddress(userId, addressId);

        if (req.getLabel()     != null) address.setLabel(blankToNull(req.getLabel()));
        if (req.getCity()      != null) address.setCity(req.getCity());
        if (req.getStreet()    != null) address.setStreet(req.getStreet());
        if (req.getHouse()     != null) address.setHouse(req.getHouse());
        if (req.getApartment() != null) address.setApartment(blankToNull(req.getApartment()));
        if (req.getEntrance()  != null) address.setEntrance(blankToNull(req.getEntrance()));
        if (req.getFloor()     != null) address.setFloor(blankToNull(req.getFloor()));
        if (req.getLatitude()  != null) address.setLatitude(req.getLatitude());
        if (req.getLongitude() != null) address.setLongitude(req.getLongitude());

        if (Boolean.TRUE.equals(req.getDefaultAddress())) {
            addressRepo.clearDefaultForUser(userId);
            address.setDefaultAddress(true);
        } else if (Boolean.FALSE.equals(req.getDefaultAddress())) {
            address.setDefaultAddress(false);
        }

        return toResponse(addressRepo.save(address));
    }

    public UserAddressDto.Response setDefault(UUID userId, UUID addressId) {
        UserAddress address = findOwnedAddress(userId, addressId);
        addressRepo.clearDefaultForUser(userId);
        address.setDefaultAddress(true);
        return toResponse(addressRepo.save(address));
    }

    public void delete(UUID userId, UUID addressId) {
        UserAddress address = findOwnedAddress(userId, addressId);
        addressRepo.delete(address);
    }

    private UserAddress findOwnedAddress(UUID userId, UUID addressId) {
        requireUserId(userId);
        return addressRepo.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "ADDRESS_NOT_FOUND: Address not found"));
    }

    private void requireUserId(UUID userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED: Missing authenticated user id");
        }
    }

    private UserAddressDto.Response toResponse(UserAddress a) {
        return UserAddressDto.Response.builder()
                .id(a.getId())
                .userId(a.getUserId())
                .label(a.getLabel())
                .city(a.getCity())
                .street(a.getStreet())
                .house(a.getHouse())
                .apartment(a.getApartment())
                .entrance(a.getEntrance())
                .floor(a.getFloor())
                .latitude(a.getLatitude())
                .longitude(a.getLongitude())
                .defaultAddress(a.isDefaultAddress())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
