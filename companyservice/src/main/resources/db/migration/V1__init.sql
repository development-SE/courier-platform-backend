CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Companies table
CREATE TABLE companies (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name        VARCHAR(255) NOT NULL,
    bin         VARCHAR(12)  NOT NULL UNIQUE,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE INDEX idx_companies_bin ON companies(bin);

-- Employees table (MANAGER users linked to a company)
CREATE TABLE employees (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    first_name  VARCHAR(100) NOT NULL,
    last_name   VARCHAR(100) NOT NULL,
    email       VARCHAR(255) NOT NULL UNIQUE,
    phone       VARCHAR(30),
    company_id  UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    auth_user_id UUID,           -- UUID from auth-service after registration
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE INDEX idx_employees_company_id  ON employees(company_id);
CREATE INDEX idx_employees_email       ON employees(email);
CREATE INDEX idx_employees_auth_user_id ON employees(auth_user_id);

-- Addresses table (company addresses only)
CREATE TABLE addresses (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    company_id  UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    street      VARCHAR(255) NOT NULL,
    house       VARCHAR(20)  NOT NULL,
    apartment   VARCHAR(20),
    entrance    VARCHAR(20),
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE INDEX idx_addresses_company_id ON addresses(company_id);