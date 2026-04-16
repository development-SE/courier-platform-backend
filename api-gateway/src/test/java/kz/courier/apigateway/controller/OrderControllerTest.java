package kz.courier.apigateway.controller;

import io.jsonwebtoken.Claims;
import kz.courier.apigateway.dto.request.order.OrderListFilterDto;
import kz.courier.apigateway.dto.response.ApiResponse;
import kz.courier.apigateway.grpc.AuthContext;
import kz.courier.apigateway.grpc.OrderClient;
import kz.courier.apigateway.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock
    private OrderClient orderClient;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private Claims claims;

    @Test
    void listOrdersMapsRestQueryParamsToGrpcFilter() {
        String token = "token";
        String userId = UUID.randomUUID().toString();
        String companyId = UUID.randomUUID().toString();
        OffsetDateTime fromDate = OffsetDateTime.parse("2026-04-01T00:00:00Z");
        OffsetDateTime toDate = OffsetDateTime.parse("2026-04-15T00:00:00Z");

        when(jwtUtil.extractAllClaims(token)).thenReturn(claims);
        when(claims.getSubject()).thenReturn(userId);
        when(claims.get("role", String.class)).thenReturn("ADMIN");
        when(claims.get("companyId", String.class)).thenReturn(companyId);
        when(orderClient.listOrders(any(OrderListFilterDto.class), any(AuthContext.class)))
                .thenReturn(ApiResponse.success(Map.of("orders", java.util.List.of())));

        OrderController controller = new OrderController(orderClient, jwtUtil);
        var response = controller.listOrders(
                "Bearer " + token,
                companyId,
                userId,
                null,
                "NEW",
                fromDate,
                toDate,
                10.0,
                100.0,
                2,
                25,
                "totalAmount,desc",
                "createdAt",
                true);

        ArgumentCaptor<OrderListFilterDto> filterCaptor = ArgumentCaptor.forClass(OrderListFilterDto.class);
        ArgumentCaptor<AuthContext> authCaptor = ArgumentCaptor.forClass(AuthContext.class);
        verify(orderClient).listOrders(filterCaptor.capture(), authCaptor.capture());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(companyId, filterCaptor.getValue().getCompanyId());
        assertEquals(userId, filterCaptor.getValue().getUserId());
        assertEquals("NEW", filterCaptor.getValue().getStatus());
        assertEquals(fromDate, filterCaptor.getValue().getFromDate());
        assertEquals(toDate, filterCaptor.getValue().getToDate());
        assertEquals(10.0, filterCaptor.getValue().getMinAmount());
        assertEquals(100.0, filterCaptor.getValue().getMaxAmount());
        assertEquals("totalAmount", filterCaptor.getValue().getSortBy());
        assertEquals(true, filterCaptor.getValue().isSortDesc());
        assertEquals(companyId, authCaptor.getValue().companyId());
    }
}
