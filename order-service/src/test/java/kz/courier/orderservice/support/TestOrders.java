package kz.courier.orderservice.support;

import kz.courier.order.v1.Address;
import kz.courier.order.v1.AddressType;
import kz.courier.order.v1.ContactInfo;
import kz.courier.order.v1.CreateOrderRequest;
import kz.courier.order.v1.OrderItem;
import kz.courier.order.v1.ParcelSize;
import kz.courier.order.v1.ServiceType;

public final class TestOrders {

    private TestOrders() {
    }

    public static CreateOrderRequest readyCustomOrder() {
        return baseRequest()
                .setServiceType(ServiceType.STANDARD)
                .build();
    }

    public static CreateOrderRequest newCompanyOrder(String companyId) {
        return baseRequest()
                .setServiceType(ServiceType.STANDARD)
                .setCompanyId(companyId)
                .build();
    }

    private static CreateOrderRequest.Builder baseRequest() {
        return CreateOrderRequest.newBuilder()
                .addItems(OrderItem.newBuilder()
                        .setItemId("item-1")
                        .setName("Pizza")
                        .setQuantity(2)
                        .setPrice(10.5)
                        .build())
                .setComment("Leave at the door")
                .setParcelSize(ParcelSize.MEDIUM)
                .setDeliveryAddress(address("Almaty", "Delivery Street", "10", 43.238949, 76.889709))
                .setPickupAddress(address("Almaty", "Pickup Street", "20", 43.250000, 76.900000))
                .setRecipientInfo(contact("Client", "Receiver", "+77010000001"))
                .setPickupInfo(contact("Store", "Manager", "+77010000002"));
    }

    public static Address address(String city, String street, String house, double latitude, double longitude) {
        return Address.newBuilder()
                .setType(AddressType.USER)
                .setCity(city)
                .setStreet(street)
                .setHouse(house)
                .setLatitude(latitude)
                .setLongitude(longitude)
                .build();
    }

    public static ContactInfo contact(String name, String surname, String phone) {
        return ContactInfo.newBuilder()
                .setName(name)
                .setSurname(surname)
                .setPhone(phone)
                .build();
    }
}
