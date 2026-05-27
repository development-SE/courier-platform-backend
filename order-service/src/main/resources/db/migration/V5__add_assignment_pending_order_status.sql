ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_status_check;

ALTER TABLE orders
    ADD CONSTRAINT orders_status_check
        CHECK (status IN (
            'NEW', 'ACCEPTED', 'PREPARING', 'READY',
            'ASSIGNMENT_PENDING',
            'ASSIGNED', 'PICKED_UP', 'IN_TRANSIT',
            'DELIVERY_CONFIRMATION_PENDING',
            'DELIVERED', 'CANCELLED', 'REJECTED'
        ));
