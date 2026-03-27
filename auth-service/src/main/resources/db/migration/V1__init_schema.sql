CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE users (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email             VARCHAR(255) NOT NULL UNIQUE,
    password_hash     VARCHAR(255) NOT NULL,
    phone             VARCHAR(20) UNIQUE,
    first_name        VARCHAR(100) NOT NULL,
    last_name         VARCHAR(100) NOT NULL,
    push_consent      BOOLEAN DEFAULT FALSE,
    role              VARCHAR(20)  NOT NULL CHECK (role IN ('CLIENT','COURIER','PARTNER','ADMIN')),
    is_active         BOOLEAN DEFAULT TRUE,
    is_email_verified BOOLEAN DEFAULT FALSE,
    created_at        TIMESTAMP WITH TIME ZONE DEFAULT now(),
    updated_at        TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE TABLE login_logs (
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ip_address INET,
    user_agent TEXT,
    success    BOOLEAN NOT NULL,
    timestamp  TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE TABLE confirmation_tokens (
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token      VARCHAR(255) NOT NULL UNIQUE,
    type       VARCHAR(20) NOT NULL CHECK (type IN ('EMAIL_VERIFY','PASSWORD_RESET')),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used       BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_confirmation_token ON confirmation_tokens(token);