package kz.courier.courierservice.service;

import kz.courier.courierservice.dto.CourierDto;
import kz.courier.courierservice.entity.CourierProfile;
import kz.courier.courierservice.entity.CourierType;
import kz.courier.courierservice.entity.EmploymentStatus;
import kz.courier.courierservice.entity.TransportType;
import kz.courier.courierservice.exception.BusinessException;
import kz.courier.courierservice.grpc.AuthGrpcClient;
import kz.courier.courierservice.repository.CourierProfileRepository;
import kz.courier.courierservice.security.GatewayPrincipalProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourierServiceAuthorizationTest {

    @Mock
    private CourierProfileRepository courierProfileRepository;

    @Mock
    private AuthGrpcClient authGrpcClient;

    private final GatewayPrincipalProvider gatewayPrincipalProvider = new GatewayPrincipalProvider();

    private CourierService courierService;

    @BeforeEach
    void setUp() {
        courierService = new CourierService(
                courierProfileRepository,
                gatewayPrincipalProvider,
                authGrpcClient
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }
    // FIX ERRORS IN COMMENTED TESTS
//    @Test
//    void activeCourierCanCreateOwnOnboardingProfile() {
//        UUID courierUserId = UUID.randomUUID();
//        setPrincipal(courierUserId, "COURIER");
//        when(authGrpcClient.getUser(courierUserId)).thenReturn(authUser(courierUserId, "COURIER", true, null));
//        when(courierProfileRepository.existsByUserId(courierUserId)).thenReturn(false);
//        when(courierProfileRepository.save(any(CourierProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
//
//        courierService.create(new CourierDto.CreateCourierRequest(
//                courierUserId,
//                UUID.randomUUID(),
//                CourierType.EMPLOYEE,
//                EmploymentStatus.ACTIVE,
//                TransportType.BIKE,
//                true,
//                true,
//                10,
//                "self",
//                List.of()
//        ));
//
//        ArgumentCaptor<CourierProfile> captor = ArgumentCaptor.forClass(CourierProfile.class);
//        verify(courierProfileRepository).save(captor.capture());
//        CourierProfile saved = captor.getValue();
//        assertThat(saved.getUserId()).isEqualTo(courierUserId);
//        assertThat(saved.getCompanyId()).isNull();
//        assertThat(saved.getCourierType()).isEqualTo(CourierType.CONTRACTOR);
//        assertThat(saved.getEmploymentStatus()).isEqualTo(EmploymentStatus.ONBOARDING);
//        assertThat(saved.getTransportType()).isEqualTo(TransportType.BIKE);
//        assertThat(saved.isVerified()).isFalse();
//        assertThat(saved.isCanTakeOrders()).isFalse();
//        assertThat(saved.getMaxActiveOrders()).isEqualTo(1);
//        assertThat(saved.getSchedules()).isEmpty();
//    }

    @Test
    void selfServiceRejectsMismatchedUserId() {
        UUID callerId = UUID.randomUUID();
        UUID requestedUserId = UUID.randomUUID();
        setPrincipal(callerId, "COURIER");
        when(authGrpcClient.getUser(requestedUserId)).thenReturn(authUser(requestedUserId, "COURIER", true, null));

        assertThatThrownBy(() -> courierService.create(selfRequest(requestedUserId, TransportType.CAR)))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("FORBIDDEN");

        verify(courierProfileRepository, never()).save(any());
    }

    @Test
    void nonCourierAuthUserCannotReceiveCourierProfile() {
        UUID userId = UUID.randomUUID();
        setPrincipal(userId, "COURIER");
        when(authGrpcClient.getUser(userId)).thenReturn(authUser(userId, "CLIENT", true, null));

        assertThatThrownBy(() -> courierService.create(selfRequest(userId, TransportType.CAR)))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("USER_NOT_COURIER");

        verify(courierProfileRepository, never()).save(any());
    }

    @Test
    void inactiveCourierIsRejected() {
        UUID userId = UUID.randomUUID();
        setPrincipal(userId, "COURIER");
        when(authGrpcClient.getUser(userId)).thenReturn(authUser(userId, "COURIER", false, null));

        assertThatThrownBy(() -> courierService.create(selfRequest(userId, TransportType.CAR)))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("AUTH_USER_INACTIVE");

        verify(courierProfileRepository, never()).save(any());
    }

    @Test
    void authServiceUnavailablePreventsProfileCreation() {
        UUID userId = UUID.randomUUID();
        setPrincipal(userId, "COURIER");
        when(authGrpcClient.getUser(userId)).thenThrow(new BusinessException(
                "AUTH_SERVICE_UNAVAILABLE", "Auth service is unavailable"));

        assertThatThrownBy(() -> courierService.create(selfRequest(userId, TransportType.CAR)))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("AUTH_SERVICE_UNAVAILABLE");

        verify(courierProfileRepository, never()).save(any());
    }

//    @Test
//    void missingTransportStillCreatesOnboardingProfile() {
//        UUID userId = UUID.randomUUID();
//        setPrincipal(userId, "COURIER");
//        when(authGrpcClient.getUser(userId)).thenReturn(authUser(userId, "COURIER", true, null));
//        when(courierProfileRepository.existsByUserId(userId)).thenReturn(false);
//        when(courierProfileRepository.save(any(CourierProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
//
//        courierService.create(selfRequest(userId, null));
//
//        ArgumentCaptor<CourierProfile> captor = ArgumentCaptor.forClass(CourierProfile.class);
//        verify(courierProfileRepository).save(captor.capture());
//        assertThat(captor.getValue().getTransportType()).isNull();
//        assertThat(captor.getValue().getEmploymentStatus()).isEqualTo(EmploymentStatus.ONBOARDING);
//        assertThat(captor.getValue().isCanTakeOrders()).isFalse();
//    }
//
//    @Test
//    void adminCanCreateProfileForValidCourier() {
//        UUID adminId = UUID.randomUUID();
//        UUID courierUserId = UUID.randomUUID();
//        setPrincipal(adminId, "ADMIN");
//        when(authGrpcClient.getUser(courierUserId)).thenReturn(authUser(courierUserId, "COURIER", true, null));
//        when(courierProfileRepository.existsByUserId(courierUserId)).thenReturn(false);
//        when(courierProfileRepository.save(any(CourierProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
//
//        courierService.create(new CourierDto.CreateCourierRequest(
//                courierUserId,
//                null,
//                CourierType.CONTRACTOR,
//                EmploymentStatus.ACTIVE,
//                TransportType.CAR,
//                true,
//                true,
//                3,
//                null,
//                null
//        ));
//
//        ArgumentCaptor<CourierProfile> captor = ArgumentCaptor.forClass(CourierProfile.class);
//        verify(courierProfileRepository).save(captor.capture());
//        assertThat(captor.getValue().isVerified()).isTrue();
//        assertThat(captor.getValue().isCanTakeOrders()).isTrue();
//        assertThat(captor.getValue().getEmploymentStatus()).isEqualTo(EmploymentStatus.ACTIVE);
//    }

    @Test
    void managerCannotCreateCourierForAnotherCompany() {
        UUID managerId = UUID.randomUUID();
        UUID courierUserId = UUID.randomUUID();
        UUID managerCompanyId = UUID.randomUUID();
        UUID otherCompanyId = UUID.randomUUID();
        setPrincipal(managerId, "MANAGER");
        when(authGrpcClient.getUser(courierUserId)).thenReturn(authUser(courierUserId, "COURIER", true, null));
        when(authGrpcClient.getUser(managerId)).thenReturn(authUser(managerId, "MANAGER", true, managerCompanyId));

        assertThatThrownBy(() -> courierService.create(new CourierDto.CreateCourierRequest(
                courierUserId,
                otherCompanyId,
                CourierType.EMPLOYEE,
                EmploymentStatus.ONBOARDING,
                TransportType.CAR,
                false,
                false,
                1,
                null,
                List.of()
        )))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("FORBIDDEN");

        verify(courierProfileRepository, never()).save(any());
    }

    private CourierDto.CreateCourierRequest selfRequest(UUID userId, TransportType transportType) {
        return new CourierDto.CreateCourierRequest(
                userId,
                null,
                null,
                null,
                transportType,
                true,
                true,
                20,
                "self",
                List.of()
        );
    }

    private AuthGrpcClient.AuthUser authUser(UUID userId, String role, boolean active, UUID companyId) {
        return new AuthGrpcClient.AuthUser(userId, role, active, true, companyId);
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
