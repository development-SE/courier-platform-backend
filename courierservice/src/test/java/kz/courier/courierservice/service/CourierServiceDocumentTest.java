package kz.courier.courierservice.service;

import kz.courier.courierservice.dto.CourierDto;
import kz.courier.courierservice.entity.*;
import kz.courier.courierservice.exception.BusinessException;
import kz.courier.courierservice.grpc.AuthGrpcClient;
import kz.courier.courierservice.repository.CourierProfileRepository;
import kz.courier.courierservice.repository.CourierDocumentRepository;
import kz.courier.courierservice.security.GatewayPrincipalProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CourierServiceDocumentTest {

    @Mock
    private CourierProfileRepository courierProfileRepository;

    @Mock
    private CourierDocumentRepository courierDocumentRepository;

    @Mock
    private AuthGrpcClient authGrpcClient;

    private final GatewayPrincipalProvider gatewayPrincipalProvider = new GatewayPrincipalProvider();

    private CourierService courierService;

    @BeforeEach
    void setUp() {
        courierService = new CourierService(
                courierProfileRepository,
                gatewayPrincipalProvider,
                authGrpcClient,
                courierDocumentRepository
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void courierCanUploadDocument() {
        UUID courierId = UUID.randomUUID();
        setPrincipal(courierId, "COURIER");

        CourierProfile profile = CourierProfile.builder()
                .id(courierId)
                .transportType(TransportType.BIKE)
                .employmentStatus(EmploymentStatus.ONBOARDING)
                .verified(false)
                .schedules(new ArrayList<>())
                .documents(new ArrayList<>())
                .build();

        when(courierProfileRepository.findWithSchedulesById(courierId)).thenReturn(Optional.of(profile));
        when(courierDocumentRepository.findByCourierIdAndDocumentType(courierId, DocumentType.IDENTIFICATION))
                .thenReturn(Optional.empty());

        CourierDto.CourierProfileResponse response = courierService.uploadDocument(
                courierId,
                DocumentType.IDENTIFICATION,
                "ID12345",
                "/download/id.pdf"
        );

        verify(courierDocumentRepository).save(any(CourierDocument.class));
        assertThat(response).isNotNull();
    }

    @Test
    void otherCourierCannotUploadDocumentForAnother() {
        UUID callerId = UUID.randomUUID();
        UUID otherCourierId = UUID.randomUUID();
        setPrincipal(callerId, "COURIER");

        assertThatThrownBy(() -> courierService.uploadDocument(
                otherCourierId,
                DocumentType.IDENTIFICATION,
                "ID12345",
                "/download/id.pdf"
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("You are not allowed to upload your document");
    }

    @Test
    void adminCanVerifyAndAutoActivateBikeCourier() {
        UUID adminId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        setPrincipal(adminId, "ADMIN");

        CourierProfile profile = CourierProfile.builder()
                .id(courierId)
                .transportType(TransportType.BIKE)
                .employmentStatus(EmploymentStatus.ONBOARDING)
                .verified(false)
                .canTakeOrders(false)
                .schedules(new ArrayList<>())
                .documents(new ArrayList<>())
                .build();

        CourierDocument doc = CourierDocument.builder()
                .id(docId)
                .courier(profile)
                .documentType(DocumentType.IDENTIFICATION)
                .status(DocumentStatus.PENDING)
                .build();

        when(courierProfileRepository.findWithSchedulesById(courierId)).thenReturn(Optional.of(profile));
        when(courierDocumentRepository.findById(docId)).thenReturn(Optional.of(doc));
        
        when(courierDocumentRepository.findByCourierId(courierId)).thenReturn(List.of(
                CourierDocument.builder()
                        .documentType(DocumentType.IDENTIFICATION)
                        .status(DocumentStatus.APPROVED)
                        .build()
        ));
        
        when(courierProfileRepository.save(any(CourierProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CourierDto.VerifyDocumentRequest req = new CourierDto.VerifyDocumentRequest(DocumentStatus.APPROVED, null);
        CourierDto.CourierProfileResponse response = courierService.verifyDocument(courierId, docId, req);

        assertThat(response.isVerified()).isTrue();
        assertThat(response.employmentStatus()).isEqualTo(EmploymentStatus.ACTIVE);
        assertThat(response.canTakeOrders()).isTrue();
    }

    @Test
    void adminCanVerifyButCarCourierStaysOnboardingIfLicenseMissing() {
        UUID adminId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        setPrincipal(adminId, "ADMIN");

        CourierProfile profile = CourierProfile.builder()
                .id(courierId)
                .transportType(TransportType.CAR)
                .employmentStatus(EmploymentStatus.ONBOARDING)
                .verified(false)
                .canTakeOrders(false)
                .schedules(new ArrayList<>())
                .documents(new ArrayList<>())
                .build();

        CourierDocument doc = CourierDocument.builder()
                .id(docId)
                .courier(profile)
                .documentType(DocumentType.IDENTIFICATION)
                .status(DocumentStatus.PENDING)
                .build();

        when(courierProfileRepository.findWithSchedulesById(courierId)).thenReturn(Optional.of(profile));
        when(courierDocumentRepository.findById(docId)).thenReturn(Optional.of(doc));
        
        when(courierDocumentRepository.findByCourierId(courierId)).thenReturn(List.of(
                CourierDocument.builder()
                        .documentType(DocumentType.IDENTIFICATION)
                        .status(DocumentStatus.APPROVED)
                        .build()
        ));
        
        when(courierProfileRepository.save(any(CourierProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CourierDto.VerifyDocumentRequest req = new CourierDto.VerifyDocumentRequest(DocumentStatus.APPROVED, null);
        CourierDto.CourierProfileResponse response = courierService.verifyDocument(courierId, docId, req);

        assertThat(response.isVerified()).isFalse();
        assertThat(response.employmentStatus()).isEqualTo(EmploymentStatus.ONBOARDING);
        assertThat(response.canTakeOrders()).isFalse();
    }

    @Test
    void adminRejectionResetsVerificationStatus() {
        UUID adminId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        setPrincipal(adminId, "ADMIN");

        CourierProfile profile = CourierProfile.builder()
                .id(courierId)
                .transportType(TransportType.BIKE)
                .employmentStatus(EmploymentStatus.ACTIVE)
                .verified(true)
                .canTakeOrders(true)
                .schedules(new ArrayList<>())
                .documents(new ArrayList<>())
                .build();

        CourierDocument doc = CourierDocument.builder()
                .id(docId)
                .courier(profile)
                .documentType(DocumentType.IDENTIFICATION)
                .status(DocumentStatus.APPROVED)
                .build();

        when(courierProfileRepository.findWithSchedulesById(courierId)).thenReturn(Optional.of(profile));
        when(courierDocumentRepository.findById(docId)).thenReturn(Optional.of(doc));
        
        when(courierDocumentRepository.findByCourierId(courierId)).thenReturn(List.of(
                CourierDocument.builder()
                        .documentType(DocumentType.IDENTIFICATION)
                        .status(DocumentStatus.REJECTED)
                        .build()
        ));
        
        when(courierProfileRepository.save(any(CourierProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CourierDto.VerifyDocumentRequest req = new CourierDto.VerifyDocumentRequest(DocumentStatus.REJECTED, "Invalid document");
        CourierDto.CourierProfileResponse response = courierService.verifyDocument(courierId, docId, req);

        assertThat(response.isVerified()).isFalse();
        assertThat(response.employmentStatus()).isEqualTo(EmploymentStatus.ONBOARDING);
        assertThat(response.canTakeOrders()).isFalse();
    }

    private void setPrincipal(UUID userId, String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId.toString(),
                null,
                java.util.Arrays.stream(roles)
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList()
        ));
    }
}
