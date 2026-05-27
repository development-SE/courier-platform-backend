# Capacity-Aware Assignment MVP

This logistics feature is not a full VRP solver. It is a lightweight route
insertion heuristic that lets one courier carry multiple active orders when
capacity and route constraints allow it.

For each auto-assignment request, logistics loads the order snapshot, nearby
online courier locations, and courier profile metadata. It rejects inactive,
blocked, stale-location, over-capacity, or over-workload couriers. For active
routes it evaluates valid pickup/dropoff insertion positions while keeping each
pickup before its dropoff and leaving completed stops immutable.

Normal customer flow is backend-driven:

1. The client app creates an order through the API gateway.
2. `order-service` persists the order and publishes `ORDER_CREATED` to the
   configured `kafka.topic.order-events` topic after transaction commit.
3. `logistics-service` consumes the event and runs capacity-aware assignment
   using a configured system principal.
4. Successful assignments publish the existing logistics assignment events and
   `order-service` moves the order to `ASSIGNED`.
5. Failed automatic assignment is persisted as unresolved `MANUAL_REQUIRED` and
   `order-service` maps that logistics state to customer-facing
   `ASSIGNMENT_PENDING`.

The REST endpoint `POST /assignments/auto/{orderId}` remains available for
admin retry, demos, and debugging. It is not intended for normal client apps.

Parcel demand units:

- SMALL = 1
- MEDIUM = 2
- LARGE = 4

Vehicle capacity units:

- FOOT = 1
- BIKE = 3
- SCOOTER = 4
- CAR = 10
- VAN = 30

If parcel size is unavailable for legacy orders, demand falls back to total item
quantity: 1 item is SMALL, 2-3 items are MEDIUM, and 4 or more items are LARGE.
If item data is also unavailable, the order is treated as SMALL.

The scoring formula is:

```text
score =
  addedRouteDistance * 0.45
+ activeOrdersPenalty * 0.20
+ capacityUsagePenalty * 0.20
+ distanceToPickupPenalty * 0.15
```

The model is intentionally replaceable. A future OR-Tools or Timefold VRP
implementation can consume the same route and stop tables.

## Manual-required queue

When no feasible courier is found, logistics creates or updates one unresolved
`MANUAL_REQUIRED` row in `courier_assignments`. The row stores failure reason,
message, candidate counts, demand units, retry count, and retry timestamps.

Operational users can inspect the queue:

```http
GET /assignments/manual-required?page=1&pageSize=20&sortBy=createdAt&desc=false
```

Operational users can manually choose a courier:

```http
POST /assignments/manual
Content-Type: application/json

{
  "orderId": "00000000-0000-0000-0000-000000000100",
  "courierId": "00000000-0000-0000-0000-000000000200",
  "reason": "Assigned manually by manager"
}
```

Manual assignment still enforces order existence, active-assignment uniqueness,
courier profile eligibility, online/fresh location, capacity, max active orders,
pickup-before-dropoff, and completed-stop immutability. There is no force assign
mode in this MVP.

## Retry rules

Scheduling is enabled with `@Scheduled` and configured under `assignment.retry`.
Defaults:

- `enabled=true`
- `fixed-delay-ms=60000`
- `delay-seconds=60`
- `max-attempts=5`
- `batch-size=10`

Retryable reasons are `NO_ONLINE_COURIERS`, `STALE_LOCATIONS`,
`COURIER_SERVICE_UNAVAILABLE`, `NO_CAPACITY_AVAILABLE`,
`MAX_ACTIVE_ORDERS_REACHED`, and `UNKNOWN`. Permanent reasons are
`MISSING_ORDER_COORDINATES`, `PARCEL_TOO_LARGE_FOR_ALL_VEHICLES`, and
`INVALID_ORDER_DATA`.

## Security

The API gateway already routes `/api/v1/logistics/assignments/**` to
`logistics-service` with JWT headers. Service-level authorization limits
assignment retry, manual assignment, and the manual-required queue to
`ADMIN`, `SUPER_ADMIN`, `MANAGER`, and `DIRECTOR`. `CLIENT` and `COURIER`
users cannot trigger these operational endpoints.
