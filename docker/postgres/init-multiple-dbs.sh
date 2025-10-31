#!/bin/bash
set -e

# This script runs **once** when the container is first created.
# PostgreSQL calls every *.sh in /docker-entrypoint-initdb.d alphabetically.

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL

  ----------------------------------------------------------------
  -- 1. Create one role per microservice (least-privilege)
  ----------------------------------------------------------------
  CREATE ROLE auth_svc LOGIN PASSWORD 'auth_secret';
  CREATE ROLE user_svc LOGIN PASSWORD 'user_secret';
  CREATE ROLE catalog_svc LOGIN PASSWORD 'catalog_secret';
  CREATE ROLE order_svc LOGIN PASSWORD 'order_secret';
  CREATE ROLE payment_svc LOGIN PASSWORD 'payment_secret';
  CREATE ROLE admin_svc LOGIN PASSWORD 'admin_secret';
  CREATE ROLE courier_svc LOGIN PASSWORD 'courier_secret';

  ----------------------------------------------------------------
  -- 2. Create dedicated database per service
  ----------------------------------------------------------------
  CREATE DATABASE auth_db     OWNER auth_svc     ENCODING 'UTF8' LC_COLLATE 'en_US.utf8' LC_CTYPE 'en_US.utf8';
  CREATE DATABASE user_db     OWNER user_svc;
  CREATE DATABASE catalog_db  OWNER catalog_svc;
  CREATE DATABASE order_db    OWNER order_svc;
  CREATE DATABASE payment_db  OWNER payment_svc;
  CREATE DATABASE admin_db    OWNER admin_svc;
  CREATE DATABASE courier_db  OWNER courier_svc;

  ----------------------------------------------------------------
  -- 3. Grant CONNECT + extensions
  ----------------------------------------------------------------
  GRANT CONNECT ON DATABASE auth_db     TO auth_svc;
  GRANT CONNECT ON DATABASE user_db     TO user_svc;
  GRANT CONNECT ON DATABASE catalog_db  TO catalog_svc;
  GRANT CONNECT ON DATABASE order_db    TO order_svc;
  GRANT CONNECT ON DATABASE payment_db  TO payment_svc;
  GRANT CONNECT ON DATABASE admin_db    TO admin_svc;
  GRANT CONNECT ON DATABASE courier_db  TO courier_svc;

  -- Enable useful extensions in every DB
  \c auth_db
  CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
  CREATE EXTENSION IF NOT EXISTS "pgcrypto";

  \c catalog_db
  CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

  \c order_db
  CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
  CREATE EXTENSION IF NOT EXISTS "btree_gist";   -- for time-range queries

  -- … repeat for others if needed

EOSQL