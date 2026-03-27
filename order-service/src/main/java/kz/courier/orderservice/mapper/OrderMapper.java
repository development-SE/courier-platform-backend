package kz.courier.orderservice.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;
import kz.courier.order.v1.*;
import kz.courier.orderservice.dto.OrderItemDto;
import kz.courier.orderservice.model.Address;
import kz.courier.orderservice.model.AddressType;
import kz.courier.orderservice.model.Contact;
import kz.courier.orderservice.model.Order;
import kz.courier.orderservice.model.ServiceType;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

/**
 * Pure static mapping utility between JPA entities and gRPC proto messages.
 *
 * <p>Naming convention:
 * <ul>
 *   <li>{@code toEntity} – proto → JPA entity (for persistence)</li>
 *   <li>{@code toProto}  – JPA entity → proto message (for gRPC response)</li>
 * </ul>
 */
public final class OrderMapper {

    private OrderMapper() {}

    // ── Address ──────────────────────────────────────────────────────────────

    /**
     * Maps a proto {@link kz.courier.order.v1.Address} to a JPA {@link Address} entity.
     */
    public static Address toAddressEntity(kz.courier.order.v1.Address proto) {
        return Address.builder()
                .type(AddressType.valueOf(
                        proto.getType().name()))
                .city(proto.getCity())
                .street(proto.getStreet())
                .house(proto.getHouse())
                .apartment(proto.hasApartment() ? proto.getApartment() : null)
                .entrance(proto.hasEntrance()   ? proto.getEntrance()  : null)
                .floor(proto.hasFloor()         ? proto.getFloor()     : null)
                .latitude(proto.getLatitude())
                .longitude(proto.getLongitude())
                .build();
    }

    /**
     * Maps a JPA {@link Address} entity to a proto {@link kz.courier.order.v1.Address} message.
     */
    public static kz.courier.order.v1.Address toAddressProto(Address e) {
        var builder = kz.courier.order.v1.Address.newBuilder()
                .setAddressId(e.getId().toString())
                .setType(kz.courier.order.v1.AddressType.valueOf(
                        e.getType().name()))
                .setCity(e.getCity())
                .setStreet(e.getStreet())
                .setHouse(e.getHouse())
                .setLatitude(e.getLatitude())
                .setLongitude(e.getLongitude())
                .setCreatedAt(toTimestamp(e.getCreatedAt()))
                .setUpdatedAt(toTimestamp(e.getUpdatedAt()));

        if (e.getApartment() != null) builder.setApartment(e.getApartment());
        if (e.getEntrance()  != null) builder.setEntrance(e.getEntrance());
        if (e.getFloor()     != null) builder.setFloor(e.getFloor());

        return builder.build();
    }

    // ── Contact ───────────────────────────────────────────────────────────────

    /**
     * Maps a proto {@link ContactInfo} to a JPA {@link Contact} entity.
     */
    public static Contact toContactEntity(ContactInfo proto) {
        return Contact.builder()
                .name(proto.getName())
                .surname(proto.hasSurname() ? proto.getSurname() : null)
                .phone(proto.getPhone())
                .build();
    }

    /**
     * Maps a JPA {@link Contact} entity to a proto {@link ContactInfo} message.
     */
    public static ContactInfo toContactProto(Contact e) {
        var builder = ContactInfo.newBuilder()
                .setContactId(e.getId().toString())
                .setName(e.getName())
                .setPhone(e.getPhone())
                .setCreatedAt(toTimestamp(e.getCreatedAt()))
                .setUpdatedAt(toTimestamp(e.getUpdatedAt()));

        if (e.getSurname() != null) builder.setSurname(e.getSurname());

        return builder.build();
    }

    // ── Order ─────────────────────────────────────────────────────────────────

    /**
     * Deserializes JSONB items column and maps a JPA {@link Order} to a proto
     * {@link GetOrderResponse} (used in both GetOrder and ListOrders responses).
     */
    public static GetOrderResponse toGetOrderResponse(Order o, ObjectMapper objectMapper) {
        List<OrderItem> items = deserializeItems(o.getItemsJson(), objectMapper);

        return GetOrderResponse.newBuilder()
                .setOrderId(o.getId().toString())
                .setStatus(kz.courier.order.v1.OrderStatus.valueOf(o.getStatus().name()))
                .setServiceType(kz.courier.order.v1.ServiceType.valueOf(o.getServiceType().name()))
                .setComment(o.getComment() != null ? o.getComment() : "")
                .setDeliveryAddress(toAddressProto(o.getDeliveryAddress()))
                .setRecipientInfo(toContactProto(o.getRecipientContact()))
                .setPickupAddress(toAddressProto(o.getPickupAddress()))
                .setPickupInfo(toContactProto(o.getPickupContact()))
                .addAllItems(items)
                .setCreatedAt(toTimestamp(o.getCreatedAt()))
                .setUpdatedAt(toTimestamp(o.getUpdatedAt()))
                .build();
    }

    // ── Items ─────────────────────────────────────────────────────────────────

    /**
     * Serializes a list of proto {@link OrderItem} messages to a JSON string for JSONB storage.
     */
    public static String serializeItems(List<OrderItem> protoItems, ObjectMapper objectMapper) {
        List<OrderItemDto> dtos = protoItems.stream()
                .map(i -> OrderItemDto.builder()
                        .itemId(i.getItemId())
                        .name(i.getName())
                        .quantity(i.getQuantity())
                        .price(i.hasPrice() ? i.getPrice() : null)
                        .build())
                .toList();
        try {
            return objectMapper.writeValueAsString(dtos);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize order items", e);
        }
    }

    /**
     * Deserializes a JSON string from JSONB column back to proto {@link OrderItem} messages.
     */
    public static List<OrderItem> deserializeItems(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            List<OrderItemDto> dtos = objectMapper.readValue(
                    json, new TypeReference<List<OrderItemDto>>() {});
            return dtos.stream().map(dto -> {
                var b = OrderItem.newBuilder()
                        .setItemId(dto.getItemId() != null ? dto.getItemId() : "")
                        .setName(dto.getName())
                        .setQuantity(dto.getQuantity());
                if (dto.getPrice() != null) b.setPrice(dto.getPrice());
                return b.build();
            }).toList();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize order items", e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public static Timestamp toTimestamp(OffsetDateTime dt) {
        if (dt == null) return Timestamp.getDefaultInstance();
        var instant = dt.toInstant();
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    /**
     * Converts a proto {@link kz.courier.order.v1.ServiceType} to the JPA enum
     * {@link ServiceType}, stripping the proto prefix if any.
     */
    public static ServiceType toServiceTypeEntity(kz.courier.order.v1.ServiceType proto) {
        String name = proto.name();
        // proto names match JPA enum names exactly (STANDARD, SCHEDULED, EXPRESS)
        return ServiceType.valueOf(name);
    }
}
