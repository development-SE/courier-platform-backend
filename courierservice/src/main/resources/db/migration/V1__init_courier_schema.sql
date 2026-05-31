CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE courier_profiles (
    id                  UUID PRIMARY KEY,
    company_id          UUID,
    courier_type        VARCHAR(30) NOT NULL CHECK (courier_type IN ('CONTRACTOR', 'EMPLOYEE')),
    employment_status   VARCHAR(30) NOT NULL CHECK (employment_status IN ('ONBOARDING', 'ACTIVE', 'SUSPENDED', 'INACTIVE')),
    transport_type      VARCHAR(30)          CHECK (transport_type IN ('FOOT', 'BIKE', 'SCOOTER', 'CAR', 'VAN')),
    is_verified         BOOLEAN NOT NULL DEFAULT FALSE,
    can_take_orders     BOOLEAN NOT NULL DEFAULT TRUE,
    max_active_orders   INTEGER NOT NULL DEFAULT 1,
    notes               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE courier_work_schedules (
    id            BIGSERIAL PRIMARY KEY,
    courier_id    UUID NOT NULL REFERENCES courier_profiles(id) ON DELETE CASCADE,
    weekday       VARCHAR(16) NOT NULL CHECK (weekday IN (
        'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'
    )),
    start_time    TIME NOT NULL,
    end_time      TIME NOT NULL,
    timezone      VARCHAR(64) NOT NULL,
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_schedule_time_range CHECK (start_time < end_time)
);

CREATE INDEX idx_courier_profiles_company_id ON courier_profiles(company_id);
CREATE INDEX idx_courier_profiles_type ON courier_profiles(courier_type);
CREATE INDEX idx_courier_profiles_status ON courier_profiles(employment_status);
CREATE INDEX idx_courier_schedules_courier_id ON courier_work_schedules(courier_id);
CREATE INDEX idx_courier_schedules_weekday ON courier_work_schedules(weekday);
