CREATE TABLE user_addresses (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL,
    label       VARCHAR(100),
    city        VARCHAR(100) NOT NULL,
    street      VARCHAR(255) NOT NULL,
    house       VARCHAR(50)  NOT NULL,
    apartment   VARCHAR(50),
    entrance    VARCHAR(50),
    floor       VARCHAR(50),
    latitude    DOUBLE PRECISION NOT NULL,
    longitude   DOUBLE PRECISION NOT NULL,
    is_default  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE INDEX idx_user_addresses_user_id ON user_addresses(user_id);
CREATE UNIQUE INDEX uq_user_addresses_default
    ON user_addresses(user_id)
    WHERE is_default = TRUE;
