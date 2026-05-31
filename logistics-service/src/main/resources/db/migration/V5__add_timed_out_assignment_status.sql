ALTER TABLE courier_assignments DROP CONSTRAINT IF EXISTS courier_assignments_assignment_status_check;

ALTER TABLE courier_assignments
    ADD CONSTRAINT courier_assignments_assignment_status_check
        CHECK (assignment_status IN (
            'PENDING', 'ASSIGNED', 'ACCEPTED', 'REJECTED', 'TIMED_OUT',
            'PICKED_UP', 'IN_TRANSIT', 'ARRIVED',
            'DELIVERED', 'CANCELLED', 'FAILED', 'MANUAL_REQUIRED'
        ));
