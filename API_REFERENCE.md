# Courier Platform API Reference

This document is generated from the current Spring controllers, API Gateway routes, DTOs, and service security rules.

## Base URLs

| Component | Local URL | Notes |
| --- | --- | --- |
| API Gateway | `http://localhost:8080` | Preferred frontend/mobile entry point |
| Eureka | `http://localhost:8761` | Service discovery dashboard, not a business API |
| Auth Service | gRPC only | Exposed to clients through API Gateway auth endpoints |
| Order Service | gRPC only | Exposed to clients through API Gateway order endpoints |
| User Service | `http://localhost:8084` | Also routed by gateway under `/api/v1/users` |
| Company Service | `http://localhost:8085` | Also routed by gateway under `/api/v1/companies`, `/api/v1/employees`, `/api/v1/addresses` |
| Courier Service | `http://localhost:8087` | Also routed by gateway under `/api/v1/couriers` |
| Logistics Service | `http://localhost:8086` | Also routed by gateway under `/api/v1/logistics` |
| Notification Service | `http://localhost:8082` | Also routed by gateway under `/api/v1/notifications` |

Use gateway routes from frontend/mobile unless you are debugging a service directly.

## Authentication

The client authenticates with JWT access tokens from `POST /api/v1/auth/login`.

Example client header:

```http
Authorization: Bearer {{accessToken}}
Content-Type: application/json
```

The API Gateway validates the token and forwards trusted identity headers to downstream services:

```http
X-User-Id: {{userId}}
X-User-Roles: COURIER
X-Company-Id: {{companyId}}
```

Direct service calls must provide those `X-*` headers manually. Public auth endpoints do not require `Authorization`.

Common gateway error:

```json
{
  "success": false,
  "error": {
    "code": "FORBIDDEN",
    "message": "Access denied: Insufficient permissions"
  }
}
```

## API Gateway Service

### Service Overview

The API Gateway is the frontend-facing HTTP entry point. It owns public REST facades for auth and order gRPC services, applies JWT validation, forwards user identity headers, and routes REST traffic to user, company, courier, logistics, and notification services.

### Auth Endpoints

| Method | Path | Description | Query Params | Auth |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/auth/register` | Public user registration. Only `CLIENT` and `COURIER` roles are accepted here. | None | Public |
| `POST` | `/api/v1/auth/staff` | Create platform staff user. Current implementation only allows creating `ADMIN`. | None | `SUPER_ADMIN` |
| `POST` | `/api/v1/auth/login` | Login and receive access/refresh tokens. | None | Public |
| `POST` | `/api/v1/auth/refresh` | Rotate refresh token and receive new access/refresh tokens. | None | Public body token |
| `POST` | `/api/v1/auth/logout` | Revoke the presented refresh token. | None | Public body token |
| `POST` | `/api/v1/auth/logout-all` | Revoke all active refresh tokens for current user, or a target user for admins. | None | Authenticated; `ADMIN`/`SUPER_ADMIN` for other users |
| `GET` | `/api/v1/auth/verify` | Verify email using confirmation token. | `token` required | Public |
| `GET` | `/api/v1/auth/users` | List auth users for admin screens. | `page=1`, `size=10`, `role=ADMIN|COURIER` | `ADMIN`, `SUPER_ADMIN`, `DIRECTOR`, `MANAGER`; `ADMIN` list requires `SUPER_ADMIN` |
| `DELETE` | `/api/v1/auth/users/{id}` | Delete auth user. | None | `SUPER_ADMIN` |

Register request:

```json
{
  "email": "client1@example.com",
  "password": "Client@123",
  "phone": "+77010000001",
  "firstName": "Client",
  "lastName": "One",
  "pushConsent": true,
  "role": "CLIENT"
}
```

Register success:

```json
{
  "success": true,
  "data": {
    "userId": "00000000-0000-0000-0000-000000000101",
    "message": "Registration successful. Please check your email to verify your account."
  }
}
```

Login request:

```json
{
  "email": "client1@example.com",
  "password": "Client@123",
  "deviceId": "mobile-or-postman-device"
}
```

Login success:

```json
{
  "success": true,
  "data": {
    "accessToken": "{{accessToken}}",
    "refreshToken": "{{refreshToken}}",
    "expiresAt": 1780000000,
    "role": "CLIENT"
  }
}
```

Staff request:

```json
{
  "email": "admin1@example.com",
  "password": "Admin@123",
  "phone": "+77010000003",
  "firstName": "Admin",
  "lastName": "One",
  "pushConsent": true,
  "role": "ADMIN"
}
```

Refresh request:

```json
{
  "refreshToken": "{{refreshToken}}"
}
```

Refresh tokens are single-use rotated tokens. The auth-service stores only
`SHA-256(pepper + refreshToken)` and revokes a token family if reuse is detected.

Logout request:

```json
{
  "refreshToken": "{{refreshToken}}"
}
```

Logout all request for current user:

```json
{}
```

Admin/SuperAdmin logout all request for another user:

```json
{
  "targetUserId": "00000000-0000-0000-0000-000000000101"
}
```

### Order Endpoints

Order service is gRPC internally and exposed through gateway REST.

Enums:

```text
serviceType: STANDARD, SCHEDULED, EXPRESS
parcelSize: SMALL, MEDIUM, LARGE
address.type: USER, COMPANY
order status: NEW, ACCEPTED, PREPARING, READY, ASSIGNMENT_PENDING, ASSIGNED, PICKED_UP, IN_TRANSIT, DELIVERY_CONFIRMATION_PENDING, DELIVERED, CANCELLED, REJECTED
```

| Method | Path | Description | Query Params | Auth |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/orders` | Create order. Publishes order-created event after save. | None | Authenticated |
| `GET` | `/api/v1/orders/{orderId}` | Get one order. Owner, company-scoped user, or admin can read. | None | Authenticated |
| `PATCH` | `/api/v1/orders/{orderId}/status` | Update order status. Regular clients can only cancel their own orders. Delivery completion is blocked here. | None | Authenticated |
| `GET` | `/api/v1/orders/{orderId}/delivery-confirmation-code` | Customer fallback endpoint to read active delivery OTP. | None | Order owner or admin |
| `GET` | `/api/v1/orders` | List orders with filters. | `companyId`, `userId`, `clientId`, `status`, `fromDate`, `toDate`, `minAmount`, `maxAmount`, `page`, `size`, `sort`, `sortBy`, `sortDesc` | Authenticated |

Create order request:

```json
{
  "items": [
    {
      "itemId": "item-1",
      "name": "Package",
      "quantity": 1,
      "price": 1000.0
    }
  ],
  "companyId": "00000000-0000-0000-0000-000000000111",
  "serviceType": "STANDARD",
  "parcelSize": "SMALL",
  "comment": "Handle carefully",
  "deliveryAddress": {
    "type": "USER",
    "city": "Almaty",
    "street": "Tole Bi",
    "house": "101",
    "apartment": "45",
    "entrance": "3",
    "floor": "7",
    "latitude": 43.238949,
    "longitude": 76.889709
  },
  "recipientInfo": {
    "name": "Client",
    "surname": "One",
    "phone": "+77010000001"
  },
  "pickupAddress": {
    "type": "COMPANY",
    "city": "Almaty",
    "street": "Abay Ave",
    "house": "25A",
    "apartment": "12",
    "entrance": "2",
    "floor": "1",
    "latitude": 43.245,
    "longitude": 76.93
  },
  "pickupInfo": {
    "name": "Store",
    "surname": "Dispatch",
    "phone": "+77010000004"
  }
}
```

Create order success:

```json
{
  "success": true,
  "data": {
    "orderId": "00000000-0000-0000-0000-000000000201",
    "deliveryAddrId": "00000000-0000-0000-0000-000000000301",
    "recipientContactId": "00000000-0000-0000-0000-000000000401",
    "pickupAddrId": "00000000-0000-0000-0000-000000000302",
    "pickupContactId": "00000000-0000-0000-0000-000000000402"
  }
}
```

Update order status request:

```json
{
  "newStatus": "READY",
  "reason": "Prepared by merchant"
}
```

List orders example:

```http
GET /api/v1/orders?status=READY&page=1&size=20&sort=createdAt,desc
```

### Gateway Utility Endpoints

| Method | Path | Description | Auth |
| --- | --- | --- | --- |
| `GET` | `/api/v1/health` | Gateway health check | Public |
| `GET` | `/api/v1/ping` | Gateway ping | Public |
| `GET` | `/fallback/auth` | Auth circuit breaker fallback | Public |
| `GET` | `/fallback/order` | Order circuit breaker fallback | Public |
| `GET` | `/fallback/courier` | Courier circuit breaker fallback | Public |
| `ANY` | `/actuator/**` | (Debug/Internal) Spring Boot actuator metrics/health | Public |

## User Service

### Service Overview

User Service stores user profile data and saved customer addresses. Auth credentials live in Auth Service; this service is profile/address data for app features.

Gateway base path: `/api/v1/users`

### Endpoints

| Method | Path | Description | Query Params | Auth |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/users/debug/headers` | Debug forwarded headers. | None | Authenticated |
| `POST` | `/api/v1/users` | Create profile user record. | None | `ADMIN`, `SUPER_ADMIN` via gateway |
| `GET` | `/api/v1/users/me` | Get current profile by JWT context. Builds fallback if profile row is missing. | None | Authenticated |
| `GET` | `/api/v1/users/{id}` | Get user by id. | None | `ADMIN`, `SUPER_ADMIN` |
| `GET` | `/api/v1/users` | List users. | `page`, `size`, `search`, `role`, `sortBy`, `asc` | `ADMIN`, `SUPER_ADMIN` |
| `PUT` | `/api/v1/users/{id}` | Partial update user. | None | `ADMIN`, `SUPER_ADMIN` |
| `DELETE` | `/api/v1/users/{id}` | Delete user profile row. | None | `SUPER_ADMIN` |
| `POST` | `/api/v1/users/me/addresses` | Create current user's saved address. | None | Authenticated |
| `GET` | `/api/v1/users/me/addresses` | List current user's saved addresses. | `page`, `size` | Authenticated |
| `GET` | `/api/v1/users/me/addresses/{addressId}` | Get saved address. | None | Authenticated owner |
| `PUT` | `/api/v1/users/me/addresses/{addressId}` | Update saved address. | None | Authenticated owner |
| `PATCH` | `/api/v1/users/me/addresses/{addressId}/default` | Mark address as default. | None | Authenticated owner |
| `DELETE` | `/api/v1/users/me/addresses/{addressId}` | Delete saved address. | None | Authenticated owner |
| `GET` | `/api/v1/users/{userId}/addresses` | Admin list of user's addresses. | `page`, `size` | `ADMIN`, `SUPER_ADMIN` |

Create profile request:

```json
{
  "firstName": "Alice",
  "lastName": "Admin",
  "email": "alice@example.com",
  "phone": "+77010000010",
  "companyId": null,
  "role": "ADMIN"
}
```

Update profile request:

```json
{
  "firstName": "Alice",
  "lastName": "Updated",
  "email": "alice.updated@example.com",
  "phone": "+77010000011",
  "companyId": null,
  "role": "ADMIN",
  "active": true
}
```

Create saved address request:

```json
{
  "label": "Home",
  "city": "Almaty",
  "street": "Tole Bi",
  "house": "101",
  "apartment": "45",
  "entrance": "3",
  "floor": "7",
  "latitude": 43.238949,
  "longitude": 76.889709,
  "defaultAddress": true
}
```

Saved address success:

```json
{
  "id": "00000000-0000-0000-0000-000000000501",
  "userId": "00000000-0000-0000-0000-000000000101",
  "label": "Home",
  "city": "Almaty",
  "defaultAddress": true
}
```

## Company Service

### Service Overview

Company Service manages partner/company records, employees, and company pickup addresses. Employee creation also registers the paired auth-service user with `DIRECTOR` or `MANAGER` role.

Gateway base paths: `/api/v1/companies`, `/api/v1/employees`, `/api/v1/addresses`

### Company Endpoints

| Method | Path | Description | Query Params | Auth |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/companies` | Create company. | None | `ADMIN`, `SUPER_ADMIN` |
| `GET` | `/api/v1/companies/{id}` | Get company by id. Company roles are scoped to own company. | None | `ADMIN`, `SUPER_ADMIN`, `DIRECTOR`, `MANAGER` |
| `GET` | `/api/v1/companies` | List/search companies. Company roles see own company only. | `page`, `size`, `search` | `ADMIN`, `SUPER_ADMIN`, `DIRECTOR`, `MANAGER` |
| `PUT` | `/api/v1/companies/{id}` | Update company. | None | `ADMIN`, `SUPER_ADMIN`, `DIRECTOR` |
| `DELETE` | `/api/v1/companies/{id}` | Delete company. | None | `SUPER_ADMIN` |

Create company request:

```json
{
  "name": "FastBox Logistics",
  "bin": "123456789012"
}
```

Company response:

```json
{
  "id": "00000000-0000-0000-0000-000000000111",
  "name": "FastBox Logistics",
  "bin": "123456789012",
  "directorId": null,
  "director": null,
  "createdAt": "2026-06-01T10:00:00"
}
```

### Employee Endpoints

| Method | Path | Description | Query Params | Auth |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/employees` | Create `DIRECTOR` or `MANAGER`. Directors creating employees force role to `MANAGER`. | `companyId` required for `ADMIN` and `SUPER_ADMIN` | `ADMIN`, `SUPER_ADMIN`, `DIRECTOR` |
| `GET` | `/api/v1/employees/{id}` | Get employee by id. | None | `ADMIN`, `SUPER_ADMIN`, `DIRECTOR`, `MANAGER` |
| `GET` | `/api/v1/employees` | List/search employees. | `page`, `size`, `search`, `role`, `companyId` | `ADMIN`, `SUPER_ADMIN`, `DIRECTOR`, `MANAGER` |
| `PUT` | `/api/v1/employees/{id}` | Update employee fields. | None | `ADMIN`, `SUPER_ADMIN`, `DIRECTOR` |
| `DELETE` | `/api/v1/employees/{id}` | Delete employee and paired auth user. | None | `ADMIN`, `SUPER_ADMIN`, `DIRECTOR` |

Create director request:

```json
{
  "firstName": "Dana",
  "lastName": "Director",
  "email": "director1@example.com",
  "phone": "+77010000004",
  "password": "Director@123",
  "role": "DIRECTOR"
}
```

Create manager request:

```json
{
  "firstName": "Mira",
  "lastName": "Manager",
  "email": "manager1@example.com",
  "phone": "+77010000005",
  "password": "Manager@123",
  "role": "MANAGER"
}
```

### Company Address Endpoints

| Method | Path | Description | Query Params | Auth |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/addresses` | Create company address. Company roles use `X-Company-Id`; admin roles must pass body `companyId`. | None | `ADMIN`, `SUPER_ADMIN`, `DIRECTOR`, `MANAGER` |
| `GET` | `/api/v1/addresses/{id}` | Get company address. | None | `ADMIN`, `SUPER_ADMIN`, `DIRECTOR`, `MANAGER` |
| `GET` | `/api/v1/addresses` | List company addresses. | `page`, `size`, `companyId` | `ADMIN`, `SUPER_ADMIN`, `DIRECTOR`, `MANAGER` |
| `PUT` | `/api/v1/addresses/{id}` | Update company address. | None | `ADMIN`, `SUPER_ADMIN`, `DIRECTOR`, `MANAGER` |
| `DELETE` | `/api/v1/addresses/{id}` | Delete company address. | None | `SUPER_ADMIN`, `DIRECTOR`, `MANAGER` |

Create address request:

```json
{
  "companyId": "00000000-0000-0000-0000-000000000111",
  "street": "Abay Ave",
  "house": "25A",
  "apartment": "12",
  "entrance": "2"
}
```

## Courier Service

### Service Overview

Courier Service manages courier profiles, onboarding state, vehicle/transport metadata, max active order limits, and employee schedules.

Gateway base path: `/api/v1/couriers`

Enums:

```text
courierType: CONTRACTOR, EMPLOYEE
employmentStatus: ONBOARDING, ACTIVE, SUSPENDED, INACTIVE
transportType: FOOT, BIKE, SCOOTER, CAR, VAN
weekday: MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY
```

### Endpoints

| Method | Path | Description | Query Params | Auth |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/couriers` | Create courier profile. Courier callers can only create their own profile and service forces safe onboarding defaults. | None | `ADMIN`, `SUPER_ADMIN`, `MANAGER`, `DIRECTOR`, `COURIER` |
| `GET` | `/api/v1/couriers/me` | Get current user's courier profile. | None | Authenticated |
| `GET` | `/api/v1/couriers` | List courier profiles. | `companyId`, `page=0`, `size=10` | `ADMIN`, `SUPER_ADMIN`, `MANAGER`, `DIRECTOR` |
| `GET` | `/api/v1/couriers/{id}` | Get courier profile by courier profile id. | None | `ADMIN`, `SUPER_ADMIN`, `MANAGER`, `DIRECTOR` |
| `PUT` | `/api/v1/couriers/{id}` | Update courier profile. | None | `ADMIN`, `SUPER_ADMIN`, `MANAGER`, `DIRECTOR`, `COURIER` |
| `GET` | `/api/v1/couriers/{id}/eligibility` | Evaluate courier eligibility at a time. | `at` optional ISO date-time | Authenticated, service additionally expects privileged reader |

Create contractor request:

```json
{
  "userId": "00000000-0000-0000-0000-000000000202",
  "companyId": null,
  "courierType": "CONTRACTOR",
  "employmentStatus": "ONBOARDING",
  "transportType": "BIKE",
  "isVerified": false,
  "canTakeOrders": false,
  "maxActiveOrders": 1,
  "notes": "Self-registered courier",
  "schedules": []
}
```

Admin activation request:

```json
{
  "companyId": null,
  "courierType": "CONTRACTOR",
  "employmentStatus": "ACTIVE",
  "transportType": "BIKE",
  "isVerified": true,
  "canTakeOrders": true,
  "maxActiveOrders": 3,
  "notes": "Ready for deliveries",
  "schedules": []
}
```

Employee courier request:

```json
{
  "companyId": "00000000-0000-0000-0000-000000000111",
  "courierType": "EMPLOYEE",
  "employmentStatus": "ACTIVE",
  "transportType": "CAR",
  "isVerified": true,
  "canTakeOrders": true,
  "maxActiveOrders": 5,
  "notes": "Day shift",
  "schedules": [
    {
      "weekday": "MONDAY",
      "startTime": "09:00:00",
      "endTime": "18:00:00",
      "timezone": "Asia/Almaty",
      "active": true
    }
  ]
}
```

Success response envelope:

```json
{
  "success": true,
  "data": {
    "id": "00000000-0000-0000-0000-000000000202",
    "courierType": "CONTRACTOR",
    "employmentStatus": "ACTIVE",
    "transportType": "BIKE",
    "isVerified": true,
    "canTakeOrders": true,
    "maxActiveOrders": 3
  },
  "timestamp": "2026-06-01T10:00:00Z"
}
```

## Logistics Service

### Service Overview

Logistics Service owns courier locations, assignment lifecycle, capacity-aware dispatch, route insertion, manual-required queues, assignment retries, courier offer acceptance/rejection, and delivery confirmation handoff to Order Service.

Gateway base paths: `/api/v1/logistics/couriers`, `/api/v1/logistics/assignments`

Enums:

```text
assignmentStatus: PENDING, ASSIGNED, MANUAL_REQUIRED, ACCEPTED, REJECTED, TIMED_OUT, PICKED_UP, IN_TRANSIT, ARRIVED, DELIVERED, CANCELLED, FAILED
failureReason: NO_ONLINE_COURIERS, STALE_LOCATIONS, COURIER_SERVICE_UNAVAILABLE, MISSING_ORDER_COORDINATES, PARCEL_TOO_LARGE_FOR_ALL_VEHICLES, INVALID_ORDER_DATA, NO_CAPACITY_AVAILABLE, MAX_ACTIVE_ORDERS_REACHED, UNKNOWN
```

### Courier Location Endpoints

| Method | Path | Description | Query Params | Auth |
| --- | --- | --- | --- | --- |
| `PUT` | `/api/v1/logistics/couriers/me/location` | Upsert current courier's GPS location and online flag. Also publishes location update. | None | Authenticated `COURIER` |
| `GET` | `/api/v1/logistics/couriers/{courierId}/location` | Get courier location. Courier can read own location; admins can read any. | None | Authenticated |
| `PATCH` | `/api/v1/logistics/couriers/me/online` | Toggle current courier online/offline. Online change triggers manual-required retry. | None | Authenticated `COURIER` |
| `GET` | `/api/v1/logistics/couriers/nearby` | Find online couriers near coordinates. | `lat`, `lng` required; `radiusMeters=5000`, `limit=10` optional | `ADMIN`, `SUPER_ADMIN` |

Location update:

```json
{
  "latitude": 43.245,
  "longitude": 76.93,
  "isOnline": true
}
```

Online toggle:

```json
{
  "isOnline": true
}
```

### Assignment Endpoints

| Method | Path | Description | Query Params | Auth |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/logistics/assignments` | Create direct assignment. | None | `ADMIN`, `SUPER_ADMIN` |
| `POST` | `/api/v1/logistics/assignments/auto/{orderId}` | Run capacity-aware auto assignment. | None | `ADMIN`, `SUPER_ADMIN` |
| `POST` | `/api/v1/logistics/assignments/manual` | Dispatcher/admin selects courier with feasibility checks. | None | `ADMIN`, `SUPER_ADMIN` |
| `POST` | `/api/v1/logistics/assignments/manual-required/retry` | Trigger retry of retryable manual-required assignments now. | None | Authenticated, service requires admin |
| `GET` | `/api/v1/logistics/assignments/{id}` | Get assignment by id. | None | Authenticated |
| `GET` | `/api/v1/logistics/assignments/manual-required` | List unresolved manual-required rows. | `page`, `pageSize`, `sortBy`, `desc` | `ADMIN`, `SUPER_ADMIN` |
| `GET` | `/api/v1/logistics/assignments` | List assignments. Courier app should filter by `courierId`. | `courierId`, `orderId`, `status`, `page`, `pageSize`, `sortBy`, `desc` | Authenticated |
| `PATCH` | `/api/v1/logistics/assignments/{id}/status` | Transition assignment status. Assigned courier or admin override. | None | Authenticated |
| `POST` | `/api/v1/logistics/assignments/{id}/accept` | Courier accepts a `PENDING` offer. | None | Authenticated assigned courier |
| `POST` | `/api/v1/logistics/assignments/{id}/reject` | Courier rejects `PENDING` or `ASSIGNED` assignment. | None | Authenticated assigned courier |
| `POST` | `/api/v1/logistics/assignments/{id}/verify-delivery-code` | Verify customer OTP and complete delivery. | None | Authenticated assigned courier or admin |
| `POST` | `/api/v1/logistics/assignments/{id}/resend-delivery-code` | Resend/regenerate OTP after courier arrived. | None | Authenticated assigned courier or admin |
| `GET` | `/api/v1/logistics/assignments/{id}/history` | Assignment audit trail. | None | Authenticated |

Create direct assignment:

```json
{
  "orderId": "00000000-0000-0000-0000-000000000201",
  "courierId": "00000000-0000-0000-0000-000000000202",
  "assignedBy": "00000000-0000-0000-0000-000000000303",
  "etaMinutes": 25
}
```

Manual assignment:

```json
{
  "orderId": "00000000-0000-0000-0000-000000000201",
  "courierId": "00000000-0000-0000-0000-000000000202",
  "reason": "dispatcher-selected"
}
```

Status update:

```json
{
  "newStatus": "PICKED_UP",
  "changedBy": "00000000-0000-0000-0000-000000000202",
  "reason": "parcel-picked-up"
}
```

Reject request:

```json
{
  "reason": "Courier cannot take this order"
}
```

Verify delivery request:

```json
{
  "confirmationCode": "123456"
}
```

Assignment success:

```json
{
  "success": true,
  "data": {
    "id": "00000000-0000-0000-0000-000000000601",
    "orderId": "00000000-0000-0000-0000-000000000201",
    "courierId": "00000000-0000-0000-0000-000000000202",
    "assignmentStatus": "ACCEPTED",
    "etaMinutes": 25,
    "score": 1234.5,
    "assignmentPolicy": "OFFER"
  },
  "timestamp": "2026-06-01T10:00:00Z"
}
```

Error example:

```json
{
  "success": false,
  "errorCode": "INVALID_TRANSITION",
  "message": "Transition ASSIGNED -> ARRIVED is not allowed",
  "timestamp": "2026-06-01T10:00:00Z"
}
```

## Notification Service

### Service Overview

Notification Service stores device tokens and dispatches push messages. It also consumes backend notification events from Kafka for email/push delivery.

Gateway base path: `/api/v1/notifications`

### Endpoints

| Method | Path | Description | Query Params | Auth |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/notifications/devices` | Register or update a device token for current user. | None | Authenticated |
| `DELETE` | `/api/v1/notifications/devices/{deviceId}` | Revoke current user's device token. | None | Authenticated |
| `PATCH` | `/api/v1/notifications/devices/{deviceId}/enabled` | Enable/disable push notifications for device. | None | Authenticated |
| `POST` | `/api/v1/notifications/push/test` | Send test push to current user's registered devices. | None | `ADMIN`, `SUPER_ADMIN` |

Register device:

```json
{
  "deviceId": "expo-device-1",
  "platform": "android",
  "provider": "expo",
  "pushToken": "ExponentPushToken[demo]",
  "appVersion": "1.0.0",
  "locale": "en"
}
```

Update enabled:

```json
{
  "enabled": true
}
```

Test push:

```json
{
  "title": "Test push",
  "body": "Hello from Postman"
}
```

Success response:

```json
{
  "success": true,
  "message": "Device token registered",
  "data": null
}
```

## End-to-End Workflows

### User Registration + Login Flow

1. Register public client or courier:

```http
POST /api/v1/auth/register
```

```json
{
  "email": "client1@example.com",
  "password": "Client@123",
  "phone": "+77010000001",
  "firstName": "Client",
  "lastName": "One",
  "pushConsent": true,
  "role": "CLIENT"
}
```

2. Verify email:

```http
GET /api/v1/auth/verify?token={{token_from_email_link}}
```

3. Login:

```http
POST /api/v1/auth/login
```

```json
{
  "email": "client1@example.com",
  "password": "Client@123",
  "deviceId": "mobile-device-id"
}
```

Frontend notes:

- Store `accessToken`, `refreshToken`, `role`, and decoded `sub` user id.
- Login fails until email is verified.
- Public registration does not create `ADMIN`, `DIRECTOR`, or `MANAGER`.

### Company Onboarding Flow

1. `SUPER_ADMIN` creates `ADMIN` with `/api/v1/auth/staff`.
2. `ADMIN` creates company with `POST /api/v1/companies`.
3. `ADMIN` creates director with `POST /api/v1/employees?companyId={{companyId}}`.
4. Director verifies email and logs in.
5. Director creates managers with `POST /api/v1/employees`; role is forced to `MANAGER`.
6. Director or manager creates company pickup addresses with `POST /api/v1/addresses`.

Frontend notes:

- Company-scoped users rely on `companyId` claim in JWT. Re-login after staff creation/verification before calling company-scoped endpoints.
- `ADMIN` and `SUPER_ADMIN` must pass `companyId` query/body where required. `DIRECTOR` and `MANAGER` should not manually choose another company.

### Courier Onboarding + Availability Flow

1. Courier registers with `/api/v1/auth/register`, role `COURIER`.
2. Courier verifies email and logs in.
3. Courier creates own profile:

```http
POST /api/v1/couriers
```

```json
{
  "userId": "{{courierUserId}}",
  "transportType": "BIKE",
  "notes": "Available in Almaty"
}
```

4. Admin verifies/activates courier with `PUT /api/v1/couriers/{{courierProfileId}}`.
5. Courier sends current location:

```http
PUT /api/v1/logistics/couriers/me/location
```

```json
{
  "latitude": 43.245,
  "longitude": 76.93,
  "isOnline": true
}
```

Frontend notes:

- The courier profile id and auth user id may be different in some systems, but this code stores assignment `courierId` as the courier user id used by logistics location.
- Send location updates periodically while online. Assignment matching rejects stale locations older than 10 minutes.
- There is no WebSocket/SSE in this codebase; poll assignment endpoints or use push notifications.

### Order Creation + Dispatch Flow

1. Client creates order with `POST /api/v1/orders`.
2. Merchant/admin moves order through status, if applicable:

```http
PATCH /api/v1/orders/{{orderId}}/status
```

```json
{
  "newStatus": "READY",
  "reason": "ready-for-courier"
}
```

3. Auto assignment runs from Kafka order events or admin retries:

```http
POST /api/v1/logistics/assignments/auto/{{orderId}}
```

4. If no courier is feasible, operations inspect:

```http
GET /api/v1/logistics/assignments/manual-required?page=1&pageSize=20
```

5. Operations can manually assign:

```http
POST /api/v1/logistics/assignments/manual
```

```json
{
  "orderId": "{{orderId}}",
  "courierId": "{{courierUserId}}",
  "reason": "dispatcher-selected"
}
```

Frontend notes:

- Current auto assignment accepts orders in `READY` or `ASSIGNMENT_PENDING`.
- `STANDARD` orders may wait if `assignment.standard-batching-window-seconds` is configured above zero.
- Manual assignment still checks profile, online location, capacity, and route feasibility.

### Courier Offer + Delivery Completion Flow

1. Courier polls offered assignments:

```http
GET /api/v1/logistics/assignments?courierId={{courierUserId}}&status=PENDING&page=1&pageSize=20
```

2. Courier accepts offer:

```http
POST /api/v1/logistics/assignments/{{assignmentId}}/accept
```

3. Courier marks pickup:

```http
PATCH /api/v1/logistics/assignments/{{assignmentId}}/status
```

```json
{
  "newStatus": "PICKED_UP",
  "changedBy": "{{courierUserId}}",
  "reason": "parcel-picked-up"
}
```

4. Courier marks in transit:

```json
{
  "newStatus": "IN_TRANSIT",
  "changedBy": "{{courierUserId}}",
  "reason": "heading-to-customer"
}
```

5. Courier marks arrived:

```json
{
  "newStatus": "ARRIVED",
  "changedBy": "{{courierUserId}}",
  "reason": "arrived-at-destination"
}
```

6. Customer gets OTP:

```http
GET /api/v1/orders/{{orderId}}/delivery-confirmation-code
```

7. Courier verifies OTP:

```http
POST /api/v1/logistics/assignments/{{assignmentId}}/verify-delivery-code
```

```json
{
  "confirmationCode": "123456"
}
```

Frontend notes:

- Do not set assignment `DELIVERED` using generic status update. The service requires OTP verification endpoint.
- Generic status transitions are strict: `ACCEPTED -> PICKED_UP -> IN_TRANSIT -> ARRIVED -> verify code`.
- There is no dedicated `/me/offers` endpoint. Poll `GET /assignments?courierId={{courierUserId}}&status=PENDING`.

### Push Device Flow

1. After login, mobile app registers device:

```http
POST /api/v1/notifications/devices
```

```json
{
  "deviceId": "expo-device-1",
  "platform": "android",
  "provider": "expo",
  "pushToken": "ExponentPushToken[demo]",
  "appVersion": "1.0.0",
  "locale": "en"
}
```

2. User toggles push preference:

```http
PATCH /api/v1/notifications/devices/expo-device-1/enabled
```

```json
{
  "enabled": false
}
```

3. On logout or uninstall signal, revoke device:

```http
DELETE /api/v1/notifications/devices/expo-device-1
```

Frontend notes:

- Treat device registration as idempotent at app startup after login.
- Keep `deviceId` stable per installation.

## Frontend and Mobile Integration Notes

### State Management

- Keep auth state separate from domain state: `accessToken`, `refreshToken`, `role`, `userId`, and optional `companyId`.
- Cache IDs returned from creation flows: `companyId`, `employeeId`, `courierProfileId`, `courierUserId`, `orderId`, `assignmentId`, `addressId`.
- Decode JWT for quick UI decisions, but use API responses as source of truth.
- Refresh token on `401` once, then replay the failed request. If refresh fails, clear session.

### Required Call Order

- Verify email before login.
- Create/activate courier profile before expecting auto assignment eligibility.
- Courier must be online with fresh location before auto/manual assignment can choose them.
- Order should be `READY` or `ASSIGNMENT_PENDING` before auto/manual capacity assignment.
- Courier must mark `ARRIVED` before OTP can be generated and verified.

### Common Pitfalls

- Public registration only supports `CLIENT` and `COURIER`.
- There is no public endpoint to bootstrap the first `SUPER_ADMIN`; seed it in DB or migration/admin tooling.
- Company employee creation sends the auth verification link by email; verify before login.
- Courier offer inbox is currently implemented by filtering assignments, not a dedicated endpoint.
- `changedBy` in assignment status request is accepted by DTO, but service records current authenticated user from JWT.
- Direct service calls need `X-User-Id` and `X-User-Roles`; gateway calls need only `Authorization`.
- Some service comments mention `GET /couriers/me/location`, but the controller only exposes `GET /couriers/{courierId}/location`.

### Polling and Realtime

- No WebSocket or SSE endpoint is present.
- Courier app should poll `GET /api/v1/logistics/assignments?courierId={{courierUserId}}&status=PENDING`.
- Customer app can poll `GET /api/v1/orders/{{orderId}}` to watch status changes.
- Operations dashboard can poll manual-required assignments.
- Push notifications are supported through device tokens, but assignment push behavior depends on Kafka consumers and notification event publishing.
