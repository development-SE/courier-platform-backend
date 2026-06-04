ALTER TABLE courier_assignments
    ADD COLUMN IF NOT EXISTS route_cleaned_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS route_cleanup_reason VARCHAR(100),
    ADD COLUMN IF NOT EXISTS route_cleanup_by UUID;
