ALTER TABLE courier_assignments DROP CONSTRAINT IF EXISTS courier_assignments_assignment_status_check;

ALTER TABLE courier_assignments
    ADD CONSTRAINT courier_assignments_assignment_status_check
        CHECK (assignment_status IN (
            'PENDING', 'ASSIGNED', 'MANUAL_REQUIRED', 'ACCEPTED', 'REJECTED',
            'PICKED_UP', 'IN_TRANSIT', 'ARRIVED',
            'DELIVERED', 'CANCELLED', 'FAILED'
        ));

ALTER TABLE courier_assignments
    ADD COLUMN IF NOT EXISTS route_id UUID,
    ADD COLUMN IF NOT EXISTS score DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS demand_units INTEGER CHECK (demand_units IS NULL OR demand_units > 0),
    ADD COLUMN IF NOT EXISTS assignment_policy VARCHAR(30)
        CHECK (assignment_policy IS NULL OR assignment_policy IN ('DIRECT', 'OFFER'));

CREATE TABLE courier_routes (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    courier_id          UUID NOT NULL,
    status              VARCHAR(30) NOT NULL
        CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
    current_load_units  INTEGER NOT NULL DEFAULT 0 CHECK (current_load_units >= 0),
    max_capacity_units  INTEGER NOT NULL CHECK (max_capacity_units > 0),
    active_orders_count INTEGER NOT NULL DEFAULT 0 CHECK (active_orders_count >= 0),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE route_stops (
    id                     UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    route_id               UUID NOT NULL REFERENCES courier_routes(id) ON DELETE CASCADE,
    order_id               UUID NOT NULL,
    stop_type              VARCHAR(20) NOT NULL CHECK (stop_type IN ('PICKUP', 'DROPOFF')),
    sequence_number        INTEGER NOT NULL CHECK (sequence_number > 0),
    latitude               DOUBLE PRECISION NOT NULL,
    longitude              DOUBLE PRECISION NOT NULL,
    status                 VARCHAR(20) NOT NULL
        CHECK (status IN ('PENDING', 'ARRIVED', 'COMPLETED', 'CANCELLED')),
    estimated_arrival_time TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX ux_assignments_active_order
    ON courier_assignments(order_id)
    WHERE assignment_status NOT IN ('DELIVERED', 'CANCELLED', 'FAILED', 'REJECTED', 'MANUAL_REQUIRED');

CREATE UNIQUE INDEX ux_courier_routes_active
    ON courier_routes(courier_id)
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX ux_route_stops_route_sequence
    ON route_stops(route_id, sequence_number);

CREATE INDEX idx_assignments_route_id ON courier_assignments(route_id);
CREATE INDEX idx_routes_courier_status ON courier_routes(courier_id, status);
CREATE INDEX idx_route_stops_route ON route_stops(route_id, sequence_number);
CREATE INDEX idx_route_stops_order ON route_stops(order_id);
