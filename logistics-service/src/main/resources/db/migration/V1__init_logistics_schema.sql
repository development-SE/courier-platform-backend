CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "postgis";
CREATE EXTENSION IF NOT EXISTS "btree_gist";

-- =====================================================================
-- Table: courier_assignments
-- =====================================================================
CREATE TABLE courier_assignments (
                                     id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                     order_id                UUID NOT NULL,
                                     courier_id              UUID NOT NULL,
                                     assigned_by             UUID,
                                     assignment_status       VARCHAR(30) NOT NULL DEFAULT 'PENDING'
                                         CHECK (assignment_status IN (
                                                                      'PENDING', 'ASSIGNED', 'ACCEPTED', 'REJECTED',
                                                                      'PICKED_UP', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED', 'FAILED'
                                             )),
                                     assigned_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                     accepted_at             TIMESTAMPTZ,
                                     picked_up_at            TIMESTAMPTZ,
                                     delivered_at            TIMESTAMPTZ,
                                     cancelled_at            TIMESTAMPTZ,
                                     eta_minutes             INTEGER,
                                     actual_duration_minutes INTEGER,
                                     rejection_reason        TEXT,
                                     cancellation_reason     TEXT,
                                     created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                     updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_assignments_order_id      ON courier_assignments(order_id);
CREATE INDEX idx_assignments_courier_id    ON courier_assignments(courier_id);
CREATE INDEX idx_assignments_status        ON courier_assignments(assignment_status);
CREATE INDEX idx_assignments_assigned_at   ON courier_assignments(assigned_at DESC);
CREATE INDEX idx_assignments_order_courier ON courier_assignments(order_id, courier_id);

-- =====================================================================
-- Table: courier_locations
-- =====================================================================
CREATE TABLE courier_locations (
                                   courier_id      UUID PRIMARY KEY,
                                   latitude        DOUBLE PRECISION NOT NULL,
                                   longitude       DOUBLE PRECISION NOT NULL,
                                   location_point  GEOGRAPHY(POINT, 4326) GENERATED ALWAYS AS (
                        ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)
                    ) STORED,
                                   updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                   is_online       BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_courier_locations_geo    ON courier_locations USING GIST (location_point);
CREATE INDEX idx_courier_locations_online ON courier_locations(is_online);

-- =====================================================================
-- Table: assignment_history
-- =====================================================================
CREATE TABLE assignment_history (
                                    id              BIGSERIAL PRIMARY KEY,
                                    assignment_id   UUID NOT NULL,
                                    old_status      VARCHAR(30),
                                    new_status      VARCHAR(30) NOT NULL,
                                    changed_by      UUID,
                                    reason          TEXT,
                                    changed_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_history_assignment_id ON assignment_history(assignment_id);