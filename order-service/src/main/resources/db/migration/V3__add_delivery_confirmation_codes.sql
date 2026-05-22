ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_status_check;

ALTER TABLE orders
    ADD CONSTRAINT orders_status_check
        CHECK (status IN (
            'NEW', 'ACCEPTED', 'PREPARING', 'READY',
            'ASSIGNED', 'PICKED_UP', 'IN_TRANSIT',
            'DELIVERY_CONFIRMATION_PENDING',
            'DELIVERED', 'CANCELLED', 'REJECTED'
        ));

CREATE TABLE delivery_confirmation_codes (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    order_id        UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    courier_id      UUID NOT NULL,
    code_hash       TEXT NOT NULL,
    salt            TEXT NOT NULL,
    display_code    TEXT NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    attempts        INTEGER NOT NULL DEFAULT 0,
    max_attempts    INTEGER NOT NULL DEFAULT 5,
    consumed        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_delivery_confirmation_order_active
    ON delivery_confirmation_codes(order_id, consumed, expires_at DESC);

CREATE INDEX idx_delivery_confirmation_courier
    ON delivery_confirmation_codes(courier_id);
