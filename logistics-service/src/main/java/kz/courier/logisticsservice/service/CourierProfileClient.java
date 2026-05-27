package kz.courier.logisticsservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.courier.logisticsservice.security.GatewayPrincipalProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class CourierProfileClient {

    private final ObjectMapper objectMapper;
    private final GatewayPrincipalProvider gatewayPrincipalProvider;

    @Value("${courier-service.base-url:http://courier-service}")
    private String courierServiceBaseUrl;

    public Optional<CourierProfileSnapshot> getCourier(UUID courierId) {
        GatewayPrincipalProvider.GatewayPrincipal principal =
                gatewayPrincipalProvider.requireCurrentPrincipal();
        try {
            String payload = RestClient.builder()
                    .baseUrl(courierServiceBaseUrl)
                    .build()
                    .get()
                    .uri("/couriers/{id}", courierId)
                    .header("X-User-Id", principal.userId())
                    .header("X-User-Roles", principal.rolesCsv())
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .retrieve()
                    .body(String.class);

            JsonNode data = objectMapper.readTree(payload).path("data");
            if (data.isMissingNode() || data.isNull()) {
                return Optional.empty();
            }

            return Optional.of(new CourierProfileSnapshot(
                    UUID.fromString(data.path("id").asText()),
                    data.path("courierType").asText(),
                    data.path("employmentStatus").asText(),
                    data.path("transportType").asText(),
                    data.path("isVerified").asBoolean(false),
                    data.path("canTakeOrders").asBoolean(false),
                    data.path("maxActiveOrders").asInt(1)));
        } catch (RestClientException ex) {
            log.warn("Courier profile service unavailable courierId={} reason={}",
                    courierId, ex.getMessage());
            throw new CourierProfileUnavailableException("Courier profile service is unavailable", ex);
        } catch (Exception ex) {
            log.debug("Courier profile lookup failed courierId={} reason={}",
                    courierId, ex.getMessage());
            return Optional.empty();
        }
    }

    public static class CourierProfileUnavailableException extends RuntimeException {
        public CourierProfileUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record CourierProfileSnapshot(
            UUID courierId,
            String courierType,
            String employmentStatus,
            String transportType,
            boolean verified,
            boolean canTakeOrders,
            int maxActiveOrders
    ) {}
}
