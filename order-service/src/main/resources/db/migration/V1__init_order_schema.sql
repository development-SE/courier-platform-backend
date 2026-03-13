CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE addresses (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    -- Ownership (soft references — no FK constraint across services)
    user_id     UUID,                   -- NULL if this is company address
    company_id  UUID,                   -- NULL if this is personal address

    -- Type discriminator
    type        TEXT NOT NULL,

    -- Address components
    city        TEXT NOT NULL,
    street      TEXT NOT NULL,
    house       TEXT NOT NULL,
    apartment   TEXT,
    entrance    TEXT,
    floor       TEXT,

    -- Geo coordinates (required for routing/ETA)
    latitude    DOUBLE PRECISION NOT NULL,
    longitude   DOUBLE PRECISION NOT NULL,

    -- Audit fields
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_addresses_user_id    ON addresses(user_id);
CREATE INDEX idx_addresses_company_id ON addresses(company_id);
CREATE INDEX idx_addresses_geo        ON addresses USING GIST (ll_to_earth(latitude, longitude));
CREATE INDEX idx_addresses_type       ON addresses(type);

-- =====================================================================
-- Table: contacts
-- Reusable contact info (recipient or pickup person)
-- =====================================================================
CREATE TABLE contacts (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    name        TEXT NOT NULL,
    surname     TEXT,
    phone       TEXT NOT NULL,          -- +7... format recommended

    -- Audit
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_contacts_phone ON contacts(phone);

-- =====================================================================
-- Table: orders
-- =====================================================================
CREATE TABLE orders (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    -- Author: the authenticated client who placed the order
    -- Soft reference to auth-service users (no FK across services)
    author_id               UUID NOT NULL,

    -- Order metadata
    service_type            TEXT NOT NULL
        CHECK (service_type IN ('STANDARD', 'SCHEDULED', 'EXPRESS')),
    comment                 TEXT,

    -- References to addresses & contacts (hard FKs — same DB)
    delivery_addr_id        UUID NOT NULL REFERENCES addresses(id) ON DELETE RESTRICT,
    recipient_contact_id    UUID NOT NULL REFERENCES contacts(id)  ON DELETE RESTRICT,
    pickup_addr_id          UUID NOT NULL REFERENCES addresses(id)  ON DELETE RESTRICT,
    pickup_contact_id       UUID NOT NULL REFERENCES contacts(id)  ON DELETE RESTRICT,

    -- Items (JSONB for flexibility — no price table yet)
    items_json              JSONB NOT NULL DEFAULT '[]'::JSONB,

    -- Status lifecycle
    status                  TEXT NOT NULL
        CHECK (status IN (
            'NEW', 'ACCEPTED', 'PREPARING', 'READY',
            'ASSIGNED', 'PICKED_UP', 'IN_TRANSIT',
            'DELIVERED', 'CANCELLED', 'REJECTED'
        ))
        DEFAULT 'NEW',

    -- Audit
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes for common queries
CREATE INDEX idx_orders_author_id      ON orders(author_id);
CREATE INDEX idx_orders_status         ON orders(status);
CREATE INDEX idx_orders_delivery_addr  ON orders(delivery_addr_id);
CREATE INDEX idx_orders_pickup_addr    ON orders(pickup_addr_id);
CREATE INDEX idx_orders_created_at     ON orders(created_at DESC);