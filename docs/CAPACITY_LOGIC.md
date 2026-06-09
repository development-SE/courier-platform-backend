### 📦 Courier Capacity and Parcel Size Logic

The backend handles capacity routing using a normalized unit system where both **Parcel Sizes** (demand) and **Courier Vehicles** (supply) are converted into integer-based capacity units. This lightweight heuristic approach allows the assignment engine to quickly evaluate concurrent orders without overloading the courier. 

#### 1. Demand Units (Parcel Size)
The order's size determines how many capacity units it requires on a vehicle.
- `SMALL` = 1 unit
- `MEDIUM` = 2 units
- `LARGE` = 4 units

**Fallback Logic:** If an older or external order lacks an explicit `ParcelSize`, the system automatically falls back to inferring demand based on the `itemQuantity`:
- 1 item = 1 unit (SMALL)
- 2-3 items = 2 units (MEDIUM)
- 4 or more items = 4 units (LARGE)

#### 2. Capacity Units (Vehicle Type)
Couriers are assigned a maximum capacity based on their profile's `transportType`:
- `FOOT` = 1 unit *(Can only carry one SMALL order at a time)*
- `BIKE` = 3 units
- `SCOOTER` = 4 units
- `CAR` = 10 units
- `VAN` = 30 units

#### 3. Evaluation & Validation Process
Whenever the `CapacityAwareAssignmentService` attempts to auto-assign or manually assign a courier to an order, it performs strict checks:

1. **Physical Capacity Limit:** 
   The service evaluates: `currentLoadUnits + newOrderDemandUnits <= maxCapacityUnits`.
   *Example: A `BIKE` courier (3 units max) already carrying a `MEDIUM` order (2 units) cannot accept a new `MEDIUM` order (2 units), because 2 + 2 > 3. They are instantly rejected.*
2. **Concurrent Order Limit (`maxActiveOrders`):** 
   In addition to physical capacity, courier profiles also configure how many orders they can handle simultaneously (preventing a VAN courier from taking 30 separate small orders to 30 different addresses, which would ruin delivery SLAs). 
   The check ensures: `activeOrdersCount < maxActiveOrders`.
3. **Express Service Strictness:** 
   If the new order has `ServiceType.EXPRESS`, the system ignores the capacity limit and strictly enforces an empty route. Any courier with `activeOrders > 0` is rejected, ensuring the express order is delivered straight to the customer.

#### 4. The Scoring Heuristic
If the courier is eligible (they have enough physical capacity, haven't exceeded max active orders, and have a feasible pickup-before-dropoff route insertion), the system evaluates how "good" the match is. 

To prevent overloading one specific courier when multiple couriers are available, a **Capacity Usage Penalty** is applied during the scoring process.

```text
capacityUsagePenalty = ((currentLoad + demandUnits) / maxCapacity) * 5000 (search radius)
```

The overall score formula balances route efficiency with workload distribution:
`Score = (Added Route Distance * 0.45) + (Active Orders Penalty * 0.20) + (Capacity Usage Penalty * 0.20) + (Distance To Pickup * 0.15)`

*A lower total score signifies a better match.* By weighting the capacity penalty, the system naturally distributes the burden among the fleet rather than fully packing a single vehicle while others remain empty.