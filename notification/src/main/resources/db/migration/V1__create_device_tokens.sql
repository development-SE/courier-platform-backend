CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE device_tokens (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id       UUID NOT NULL,
    device_id     VARCHAR(255) NOT NULL,
    platform      VARCHAR(30) NOT NULL,
    provider      VARCHAR(30) NOT NULL,
    push_token    TEXT NOT NULL,
    token_hash    VARCHAR(64) NOT NULL,
    app_version   VARCHAR(50),
    locale        VARCHAR(20),
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at    TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_device_tokens_user_device ON device_tokens(user_id, device_id);
CREATE UNIQUE INDEX uq_device_tokens_hash ON device_tokens(token_hash);
CREATE INDEX idx_device_tokens_active ON device_tokens(user_id, enabled, revoked_at);
