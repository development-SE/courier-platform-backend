# Courier Platform Backend

This repository is a multi-service Spring Boot application built as a Maven reactor. The Docker setup in the project root keeps local development defaults intact while making the full stack runnable from one shared `.env` file.

## Services

- `api-gateway` on `API_GATEWAY_PORT`
- `eureka-server` on `EUREKA_SERVER_PORT`
- `auth-service` on gRPC `AUTH_GRPC_PORT`
- `user-service` on `USER_SERVICE_PORT`
- `company-service` on `COMPANY_SERVICE_PORT`
- `logistics-service` on `LOGISTICS_SERVICE_PORT`
- `notification-service` on `NOTIFICATION_SERVICE_PORT`
- `order-service` on gRPC `ORDER_GRPC_PORT`

## Infrastructure

- PostgreSQL with PostGIS on host port `POSTGRES_HOST_PORT`
- Kafka on `kafka:9092` inside Docker and `KAFKA_EXTERNAL_PORT` from the host
- Redis on `REDIS_PORT` for API Gateway rate limiting
- Eureka for service discovery

## Local Run

1. Copy `.env.example` to `.env`.
2. Update secrets and environment-specific values in `.env`.
3. Start the infrastructure you need.
4. Run the Spring Boot services from your IDE or with the Maven wrapper in each module.

The application config files still include localhost fallback values. If you run services outside Docker without env vars, they continue using addresses such as `jdbc:postgresql://localhost:5433/...` and `localhost:29092`.

## Docker Run

1. Create `.env` from `.env.example`.
2. Review `JWT_SECRET`, mail settings, and any Firebase values.
3. From the repository root run:

```bash
docker compose up --build
```

Docker Compose reads the shared root `.env`, builds every Spring Boot image, starts only the infrastructure used by this workspace, and places everything on one shared network.

## Environment Files

The root `.env` contains:

- Service ports
- JDBC URLs, DB names, usernames, and passwords
- Kafka and Redis connection values
- JWT secret
- Gateway route targets and gRPC discovery addresses
- Mail and Firebase settings
- External verification-link values

Never commit real credentials. `.env` is ignored by git and `.env.example` is the safe template to share.

## Docker Networking

Inside Docker, `localhost` means the current container itself, not another service. Container-to-container communication must use service names such as:

- `postgres:5432`
- `redis:6379`
- `kafka:9092`
- `eureka-server:8761`

That is why the Spring configs keep localhost fallbacks for IDE runs, while Docker Compose injects container-safe values from the root `.env`.

## Troubleshooting

- If PostgreSQL connections fail in Docker, confirm the JDBC URLs point to `postgres:5432`.
- If Kafka consumers fail in Docker, confirm `KAFKA_BOOTSTRAP_SERVERS=kafka:9092`.
- If the API Gateway rate limiter fails, confirm Redis is up and `REDIS_URL=redis://redis:6379`.
- If Eureka discovery looks empty, confirm `EUREKA_DEFAULT_ZONE=http://eureka-server:8761/eureka/`.
- If notification startup fails around Firebase, provide a valid credentials resource or set `FIREBASE_ENABLED=false`.
- If verification links point to the wrong host, update `API_BASE_URL` in `.env`.
