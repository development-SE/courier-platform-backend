-- TRUNCATE LOGISTICS DATA
\c courier_db
TRUNCATE TABLE courier_profiles CASCADE;
TRUNCATE TABLE outbox_messages CASCADE;

\c order_db
TRUNCATE TABLE orders CASCADE;
TRUNCATE TABLE order_items CASCADE;
TRUNCATE TABLE outbox_messages CASCADE;

\c logistics_db
TRUNCATE TABLE courier_assignments CASCADE;
TRUNCATE TABLE courier_routes CASCADE;
TRUNCATE TABLE route_stops CASCADE;
TRUNCATE TABLE courier_locations CASCADE;
TRUNCATE TABLE assignment_history CASCADE;
TRUNCATE TABLE outbox_messages CASCADE;

\c company_db
-- Only truncate if you want to wipe companies too
-- TRUNCATE TABLE companies CASCADE;

\c notification_db
TRUNCATE TABLE push_tokens CASCADE;
TRUNCATE TABLE email_logs CASCADE;
TRUNCATE TABLE outbox_messages CASCADE;

\c auth_db
-- TRUNCATE TABLE users CASCADE;

\c user_db
-- TRUNCATE TABLE users CASCADE;

-- Note: Connect as postgres user or superuser to run cross-db scripts, or run them per database.
