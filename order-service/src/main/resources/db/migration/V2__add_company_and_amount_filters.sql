ALTER TABLE orders
    ADD COLUMN company_id UUID,
    ADD COLUMN total_amount NUMERIC(12, 2) NOT NULL DEFAULT 0;

CREATE INDEX idx_orders_company_id ON orders(company_id);
CREATE INDEX idx_orders_total_amount ON orders(total_amount);
CREATE INDEX idx_orders_company_status_created ON orders(company_id, status, created_at DESC);
CREATE INDEX idx_orders_author_status_created ON orders(author_id, status, created_at DESC);
