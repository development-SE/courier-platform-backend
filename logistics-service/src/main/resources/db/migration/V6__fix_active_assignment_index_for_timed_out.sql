DROP INDEX IF EXISTS ux_assignments_active_order;

CREATE UNIQUE INDEX ux_assignments_active_order
    ON courier_assignments(order_id)
    WHERE assignment_status NOT IN (
        'DELIVERED',
        'CANCELLED',
        'FAILED',
        'REJECTED',
        'TIMED_OUT',
        'MANUAL_REQUIRED'
    );
