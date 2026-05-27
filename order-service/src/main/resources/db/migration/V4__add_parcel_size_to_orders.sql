ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS parcel_size VARCHAR(20) NOT NULL DEFAULT 'SMALL';

ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_parcel_size_check;

ALTER TABLE orders
    ADD CONSTRAINT orders_parcel_size_check
        CHECK (parcel_size IN ('SMALL', 'MEDIUM', 'LARGE'));

CREATE INDEX IF NOT EXISTS idx_orders_parcel_size ON orders(parcel_size);
