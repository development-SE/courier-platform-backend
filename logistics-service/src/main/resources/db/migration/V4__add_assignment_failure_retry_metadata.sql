ALTER TABLE courier_assignments
    ALTER COLUMN courier_id DROP NOT NULL;

ALTER TABLE courier_assignments
    ADD COLUMN IF NOT EXISTS failure_reason VARCHAR(50),
    ADD COLUMN IF NOT EXISTS failure_message TEXT,
    ADD COLUMN IF NOT EXISTS scanned_candidates INTEGER,
    ADD COLUMN IF NOT EXISTS eligible_candidates INTEGER,
    ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_retry_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS resolved_assignment_id UUID;

ALTER TABLE courier_assignments DROP CONSTRAINT IF EXISTS courier_assignments_failure_reason_check;

ALTER TABLE courier_assignments
    ADD CONSTRAINT courier_assignments_failure_reason_check
        CHECK (failure_reason IS NULL OR failure_reason IN (
            'NO_ONLINE_COURIERS',
            'STALE_LOCATIONS',
            'COURIER_SERVICE_UNAVAILABLE',
            'MISSING_ORDER_COORDINATES',
            'PARCEL_TOO_LARGE_FOR_ALL_VEHICLES',
            'INVALID_ORDER_DATA',
            'NO_CAPACITY_AVAILABLE',
            'MAX_ACTIVE_ORDERS_REACHED',
            'UNKNOWN'
        ));

CREATE INDEX IF NOT EXISTS idx_assignments_manual_required_unresolved
    ON courier_assignments(assignment_status, resolved_at, next_retry_at)
    WHERE assignment_status = 'MANUAL_REQUIRED' AND resolved_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_assignments_retry_due
    ON courier_assignments(failure_reason, retry_count, next_retry_at)
    WHERE assignment_status = 'MANUAL_REQUIRED' AND resolved_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_assignments_unresolved_manual_order
    ON courier_assignments(order_id)
    WHERE assignment_status = 'MANUAL_REQUIRED' AND resolved_at IS NULL;
