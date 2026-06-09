# Logistics Assignment Visual Testing

This project contains a separate visual testing suite for the logistics assignment engine.
The objective is to provide a way for developers and QA to visually inspect candidate scoring, routing, and assignments without affecting production state.

## Architecture

1. **Frontend (`logistics-visualizer/assignment-visualizer`)**: A Vite React app running independently from the main admin panel. It polls/queries the backend via the `/api/v1/logistics/debug/` endpoints.
2. **Backend (`logistics-service/debug`)**: A set of debug controllers and a side-effect-free preview method in `CapacityAwareAssignmentService`.
3. **Scripts (`scripts/assignment-visual-tests/`)**: Node.js scripts to run structured scenarios against the real backend APIs.
4. **DB Scripts (`scripts/dev-db/`)**: SQL scripts for external setup actions only (email verification, courier activation, company approval).

## Enabling Debug Mode

In `logistics-service`'s `application.yml`, set:
```yaml
logistics:
  debug:
    enabled: true
```
This enables the `LogisticsDebugController` and exposes `/api/v1/logistics/debug/**`.

**Important**: This must remain `false` in production. Debug endpoints are admin-only.

## Running the UI

```bash
cd logistics-visualizer/assignment-visualizer
npm install
npm run dev
```

Open `http://localhost:5173` and enter an admin JWT token to connect.

## Dashboard Tabs

| Tab          | Content                                                              |
|:-------------|:---------------------------------------------------------------------|
| Overview     | Summary cards: couriers, orders, assignments, routes                 |
| Couriers     | Table with assigned orders, availability, route, next stop, location age |
| Orders       | Assigned and unassigned orders, preview/assign actions, OTP status   |
| Assignments  | Grouped by status: PENDING, ASSIGNED, ACCEPTED, PICKED_UP, etc.     |
| Routes       | Route blocks with stop sequences, load/capacity                     |
| Simulation   | Backend simulation controls, seed data, scenario selection, logs     |

## Map Features

- **Courier markers**: Green (employee), Blue (contractor), Yellow (selected)
- **Order markers**: Red (pickup), Orange (delivery), Yellow (selected)
- **Route lines**: Gray (active), Blue (selected), Purple dashed (preview)
- **Assignment links**: Purple dashed lines connecting courier to order pickup, with midpoint tooltip showing assignment ID, status, and policy
- **Route stop circles**: Red (pickup), Orange (dropoff), Yellow (focused)

Clicking any map element selects it and highlights related objects across tabs.

## Seeded Overlays vs Backend Truth

The visualizer supports two data modes:

### Real Backend State (default)
All data comes from the real `logistics-service` database and `order-service` via gRPC. The dashboard header shows **REAL BACKEND STATE** and the snapshot timestamp.

### Visualization-Only Overlay
When you use the "Seed" controls in the Simulation tab, the frontend generates local-only test data (couriers, orders, routes, assignments) and merges it into the map display. This data is **NOT** in the real backend database.

When an overlay is active:
- A **warning banner** appears below the header showing "Seeded Overlay Active"
- The banner shows the count of overlay couriers and orders
- The overlay description clarifies it is visualization-only
- You can dismiss the overlay with the "Dismiss" button
- "Clear seeded data" in the Simulation tab removes the overlay and resets backend debug data

**Rule**: The overlay exists only for layout testing and UI prototyping. All real scenario testing must go through the scenario scripts which create real data through real APIs.

## Scenario Scripts

Located in `scripts/assignment-visual-tests/`.

### Setup
```bash
cd scripts/assignment-visual-tests
cp .env.example .env
# Edit .env with your local API URL and admin credentials
npm install
```

### Running All Scenarios
```bash
npm test
```

The runner:
1. Logs in as admin
2. Loads all scenario files from `scenarios/`
3. Skips scenarios where `meta.ready === false`
4. Runs each ready scenario, capturing pass/fail/skip
5. Writes results to `results/run-TIMESTAMP.json`
6. Prints a summary

### Available Scenarios

| # | ID | Description | Status |
|:--|:---|:------------|:-------|
| 01 | CUSTOM_BASIC | Creates courier, seeds scenario, triggers assignment | Ready |
| 02 | COMPANY_EMPLOYEE_FIRST | Tests employee priority for company orders | Ready |
| 03 | CONTRACTOR_FALLBACK | Verifies contractor selection when no employees available | Ready |
| 04 | REJECT_REASSIGN | Tests rejection followed by reassignment | Ready |
| 05 | TIMEOUT_REASSIGN | Tests timeout detection and reassignment candidates | Ready |
| 06 | CAPACITY_ROUTE_INSERTION | Tests route insertion under capacity constraints | Ready |
| 07 | EXPRESS_NEAREST | Tests nearest-courier for EXPRESS orders | Ready |
| 08 | DUPLICATE_PROTECTION | Verifies duplicate assignment prevention | Ready |
| 09 | MOTION_PICKUP_DELIVERY | Tests simulation movement and OTP flow | Ready |

### Writing New Scenarios

Create a `.js` file in `scenarios/` that exports:
```javascript
module.exports = {
  meta: {
    id: 'YOUR_SCENARIO_ID',
    title: 'Your Scenario Title',
    description: 'What this scenario tests',
    ready: true, // Set false to skip in runner
  },
  run: async (client, logger) => {
    // client = axios instance with auth
    // logger = structured logger with .log(), .pass(), .fail()
    // Use lib/api.js helpers for API calls
  },
};
```

## DB Scripts

Located in `scripts/dev-db/`. These handle external setup that cannot be done through APIs:

| Script | Purpose |
|:-------|:--------|
| `verify-users.sql` | Force email verification for `@test.local` users |
| `activate-couriers.sql` | Mark test couriers as active/verified |
| `approve-companies.sql` | Approve B2B test companies |
| `reset-debug-data.sql` | Clear test assignments and debug data |

**Critical rules**:
- Scripts only affect `DEBUG_SCENARIO_*` or `@test.local` data
- Never use DB scripts for assignment creation, delivery completion, or route completion
- Those must go through real business logic endpoints

## City Scope

The backend supports filtering by city:
- **ASTANA**: center 51.1694, 71.4491
- **ALMATY**: center 43.2389, 76.8897
- **CUSTOM**: Arbitrary bounding box

The frontend city selector sends scope parameters to the backend. The map auto-fits to returned bounds.

## Simulation

The simulation subsystem moves couriers through real backend state:
- Updates `CourierLocation` in the database
- Moves toward next pending `RouteStop`
- Marks pickup via valid lifecycle transition (not direct DB write)
- Marks arrival at delivery but does NOT bypass OTP
- Courier waits at destination until OTP is confirmed through the real endpoint

Controls: Start, Stop, Step, Start All Active Routes

## End-to-End Testing Flow

1. **Start services**: Ensure `logistics-service`, `order-service`, `courier-service`, `auth-service`, `api-gateway` are running
2. **Enable debug mode**: Set `logistics.debug.enabled: true`
3. **Run DB setup**: Execute `scripts/dev-db/verify-users.sql` and `activate-couriers.sql`
4. **Start the visualizer**: `cd assignment-visualizer && npm run dev`
5. **Connect**: Enter admin JWT in the login form
6. **Run scenarios**: In another terminal, `cd scripts/assignment-visual-tests && npm test`
7. **Watch the map**: The visualizer auto-refreshes every 4s (1s during simulation)
8. **Review results**: Check `scripts/assignment-visual-tests/results/` for JSON reports

## Screenshot Checklist for Diploma Defense

- [ ] Login screen with JWT input
- [ ] Overview tab with summary cards showing real data
- [ ] Map with courier markers (green/blue), order markers (red/orange)
- [ ] Assignment link lines (purple dashed) connecting couriers to orders
- [ ] Route polylines with stop sequence numbers
- [ ] Preview route overlay (purple dashed) showing candidate evaluation
- [ ] Candidate table with scores, eligibility, policy
- [ ] City scope switching (Astana → Almaty)
- [ ] Simulation running with courier movement
- [ ] OTP waiting state at delivery
- [ ] Scenario runner output in terminal
- [ ] Seeded overlay banner (when overlay is active)
- [ ] Orders tab with assigned/unassigned sections
- [ ] Assignments tab grouped by status
