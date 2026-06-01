# Courier Platform Backend

A microservices-based courier delivery platform built with Spring Boot, gRPC, and event-driven architecture.

## Architecture Overview

This is a distributed system following microservices architecture with:
- **API Gateway** (Spring Cloud Gateway) - Single entry point with JWT authentication, routing, and circuit breakers
- **Service Discovery** (Eureka) - Dynamic service registration and discovery
- **gRPC** - Inter-service communication via Protocol Buffers
- **Event-Driven** - Kafka for asynchronous messaging
- **Database per Service** - PostgreSQL with PostGIS for geospatial features

## Technology Stack

- **Java 21** - Primary language
- **Spring Boot 3.x** - Framework for all microservices
- **Spring Cloud Gateway** - API Gateway with reactive routing
- **Spring Cloud Netflix Eureka** - Service discovery
- **gRPC 1.76.0** - Inter-service RPC communication
- **Protocol Buffers 4.32.1** - Service contracts
- **PostgreSQL 16 + PostGIS 3.5** - Database with geospatial extensions
- **Kafka 7.6.0** - Event streaming (KRaft mode, no Zookeeper)
- **Redis** - Rate limiting and caching
- **Maven** - Build tool
- **Lombok** - Boilerplate reduction

## Services

### Core Services

1. **api-gateway** (port 8080)
   - Entry point for all client requests
   - JWT authentication via `JwtAuthenticationFilter`
   - Role-based access control via `RoleFilter`
   - Routes to downstream services with load balancing
   - Circuit breakers (Resilience4j) for fault tolerance
   - CORS configuration for web clients
   - gRPC clients for auth-service and order-service

2. **auth-service**
   - User authentication and authorization
   - JWT token generation and validation
   - Role-based access control (CLIENT, COURIER, PARTNER, ADMIN, SUPER_ADMIN, DIRECTOR, MANAGER)
   - Email verification
   - Password management
   - gRPC server exposing AuthService
   - Database: `auth_db`

3. **user-service**
   - User profile management
   - User CRUD operations
   - Admin-only endpoints for user management
   - JWT authentication required
   - Database: `user_db`

4. **order-service**
   - Order creation and management
   - Order lifecycle tracking
   - gRPC server for order operations
   - Database: `order_db`

5. **courier-service** (courierservice)
   - Courier profile management
   - Courier availability and status
   - Integration with logistics for assignments
   - Database: `courier_db`

6. **logistics-service**
   - Courier-to-order assignment logic
   - Real-time courier location tracking (PostGIS)
   - Nearby courier search with geospatial queries
   - Assignment history and status updates
   - Kafka integration for location events
   - Database: `logistics_db`

7. **company-service** (companyservice)
   - Company/partner management
   - Employee management
   - Address management
   - Database: `company_db`

8. **notification**
   - Push notification delivery
   - Email notifications
   - Event-driven via Kafka

9. **eureka-server** (port 8761)
   - Service registry
   - Health monitoring
   - Dynamic service discovery

### Common Module

Shared library containing:
- Protocol Buffer definitions (`*.proto` files)
- Generated gRPC stubs and message classes
- Common DTOs and utilities
- Located in `common/src/main/proto/kz/courier/`

## Project Structure

```
courier-platform-backend/
├── api-gateway/           # Spring Cloud Gateway
├── auth-service/          # Authentication & authorization
├── user-service/          # User management
├── order-service/         # Order management
├── courierservice/        # Courier profiles
├── logistics-service/     # Assignment & location tracking
├── companyservice/        # Company/partner management
├── notification/          # Notification delivery
├── eureka-server/         # Service discovery
├── common/                # Shared proto definitions
├── docker/                # Docker configs
│   └── postgres/          # DB init scripts
├── docker-compose.yml     # Infrastructure (Postgres, Kafka)
├── pom.xml                # Parent POM
└── start-all.ps1          # PowerShell script to start all services
```

## Development Setup

### Prerequisites

- Java 21
- Maven 3.8+
- Docker & Docker Compose
- PostgreSQL client (optional, for manual DB access)

### Starting Infrastructure

Start PostgreSQL and Kafka:

```bash
docker-compose up -d
```

This creates:
- PostgreSQL on port 5433 with PostGIS extension
- Separate databases per service (auth_db, user_db, order_db, etc.)
- Kafka on port 29092 (host access) / 9092 (container)

### Starting Services

**Option 1: PowerShell script (Windows)**

```powershell
.\start-all.ps1
```

Then in VS Code: `Ctrl+Shift+P` → "Tasks: Run Task" → "Start All Services"

**Option 2: Manual start (recommended order)**

```bash
# 1. Service Discovery
cd eureka-server && mvn spring-boot:run

# 2. Auth Service (required by gateway)
cd auth-service && mvn spring-boot:run

# 3. API Gateway
cd api-gateway && mvn spring-boot:run

# 4. Other services (any order)
cd user-service && mvn spring-boot:run
cd order-service && mvn spring-boot:run
cd courierservice && mvn spring-boot:run
cd logistics-service && mvn spring-boot:run
cd companyservice && mvn spring-boot:run
cd notification && mvn spring-boot:run
```

### Service Ports

- API Gateway: 8080
- Eureka Server: 8761
- Auth Service: (dynamic, registered with Eureka)
- User Service: (dynamic, registered with Eureka)
- Order Service: (dynamic, registered with Eureka)
- Courier Service: (dynamic, registered with Eureka)
- Logistics Service: (dynamic, registered with Eureka)
- Company Service: 8085 (static)
- PostgreSQL: 5433
- Kafka: 29092 (host), 9092 (container)
- Redis: 6379

## API Gateway Routes

All client requests go through `http://localhost:8080`. The gateway routes to services:

### Authentication (no JWT required)
- `POST /auth/register` → auth-service (gRPC)
- `POST /auth/login` → auth-service (gRPC)
- `POST /auth/refresh` → auth-service (gRPC)

### Users (JWT required)
- `GET /api/v1/users/me` → user-service (authenticated user's profile)
- `GET /api/v1/users/**` → user-service (ADMIN/SUPER_ADMIN only)
- `POST /api/v1/users/**` → user-service (ADMIN/SUPER_ADMIN only)
- `PUT /api/v1/users/**` → user-service (ADMIN/SUPER_ADMIN only)
- `DELETE /api/v1/users/**` → user-service (ADMIN/SUPER_ADMIN only)

### Companies (JWT required)
- `/api/v1/companies/**` → company-service
- `/api/v1/employees/**` → company-service
- `/api/v1/addresses/**` → company-service

### Couriers (JWT required)
- `GET /api/v1/couriers/me` → courier-service (authenticated courier's profile)
- `/api/v1/couriers/**` → courier-service

### Logistics (JWT required)
- `POST /api/v1/logistics/assignments` → logistics-service (create assignment)
- `GET /api/v1/logistics/assignments` → logistics-service (list assignments)
- `GET /api/v1/logistics/assignments/{id}` → logistics-service
- `PATCH /api/v1/logistics/assignments/{id}/status` → logistics-service
- `GET /api/v1/logistics/assignments/{id}/history` → logistics-service
- `PUT /api/v1/logistics/couriers/me/location` → logistics-service (update courier location)
- `GET /api/v1/logistics/couriers/me/location` → logistics-service
- `PATCH /api/v1/logistics/couriers/me/online` → logistics-service (set online/offline)
- `GET /api/v1/logistics/couriers/nearby` → logistics-service (find nearby couriers)

## Authentication & Authorization

### JWT Flow

1. Client registers via `POST /auth/register`
2. Client logs in via `POST /auth/login` → receives `access_token` and `refresh_token`
3. Client includes `Authorization: Bearer <access_token>` in subsequent requests
4. API Gateway validates JWT via `JwtAuthenticationFilter`
5. Gateway extracts user ID and role, passes to downstream services
6. Token expires after 24 hours; use `POST /auth/refresh` with `refresh_token`

### Roles

Defined in `common/src/main/proto/kz/courier/auth/v1/auth.proto`:

- `CLIENT` - End users placing orders
- `COURIER` - Delivery personnel
- `PARTNER` - Partner companies
- `ADMIN` - Platform administrators
- `SUPER_ADMIN` - System administrators
- `DIRECTOR` - Company directors
- `MANAGER` - Company managers

### Role-Based Access

The `RoleFilter` in API Gateway enforces role requirements per route. Example:
- `/api/v1/users/**` requires `ADMIN` or `SUPER_ADMIN`
- `/api/v1/users/me` accessible to any authenticated user

## Database Schema

Each service has its own PostgreSQL database. Connection details in `docker/postgres/init-multiple-dbs.sh`:

- `auth_db` (owner: `auth_svc`)
- `user_db` (owner: `user_svc`)
- `order_db` (owner: `order_svc`)
- `courier_db` (owner: `courier_svc`)
- `logistics_db` (owner: `logistics_svc`) - includes PostGIS for geospatial data
- `company_db` (owner: `company_svc`)

Master credentials: `courier_master` / `secret_master` (for admin access only)

## gRPC Communication

Services communicate internally via gRPC. Protocol Buffer definitions in `common/src/main/proto/`:

- `kz/courier/auth/v1/auth.proto` - AuthService (register, login, token refresh, user management)
- `kz/courier/order/v1/order.proto` - OrderService
- `kz/courier/common/v1/common.proto` - Shared messages (Response, Pagination, etc.)

### gRPC Service Discovery

Services use Eureka for gRPC endpoint discovery. Example in `api-gateway/src/main/resources/application.yml`:

```yaml
grpc:
  client:
    auth-service:
      address: discovery:///auth-service  # Discovers via Eureka
      negotiationType: PLAINTEXT
```

## Event-Driven Architecture

Kafka topics for asynchronous communication:

- Courier location updates
- Order status changes
- Notification events

Kafka runs in KRaft mode (no Zookeeper) on port 29092 for host access.

## Resilience Patterns

### Circuit Breakers

Configured in `api-gateway/src/main/resources/application.yml`:

- `authServiceCircuitBreaker` - Protects auth-service calls
- `logisticsServiceCircuitBreaker` - Protects logistics-service calls (longer timeout for PostGIS queries)

### Rate Limiting

Redis-backed rate limiter: 100 requests per second per client (default).

### Retry Logic

Gateway retries GET requests up to 3 times with exponential backoff (50ms → 500ms).

## Geospatial Features

Logistics service uses PostGIS for:
- Courier location storage (POINT geometry)
- Nearby courier search (ST_DWithin, ST_Distance)
- Geofencing and route optimization

## Configuration

### Environment Variables

- `JWT_SECRET` - Secret key for JWT signing (default: `my-super-secret-key-at-least-32chars!!`)
- Service-specific configs in each `application.yml`

### CORS

Allowed origins (configured in API Gateway):
- `http://localhost:3000` (React default)
- `http://localhost:5173` (Vite default)
- `http://10.202.6.243:8081` (custom)

## Building

### Build All Services

```bash
mvn clean install
```

### Build Specific Service

```bash
cd <service-name>
mvn clean package
```

### Generate gRPC Stubs

```bash
cd common
mvn clean compile
```

Generated classes appear in `common/target/generated-sources/protobuf/`.

## Testing

Each service has its own test suite. Run tests:

```bash
mvn test
```

## Logging

Logs are written to:
- Console (INFO level)
- `logs/<service-name>.log` (DEBUG level for service packages)

Gateway logs include request/response details via `LoggingFilter`.

## Monitoring

Actuator endpoints exposed on each service:
- `/actuator/health` - Health check
- `/actuator/info` - Service info
- `/actuator/metrics` - Metrics
- `/actuator/gateway` - Gateway routes (API Gateway only)

Eureka dashboard: `http://localhost:8761`

## Git Workflow

- Main branch: `develop`
- Current branch: `Absolute`
- Git user: `Zhandos200`

### Modified Files (Uncommitted)

Recent changes include:
- CORS configuration updates
- Auth controller and registration flow
- Security configurations across services
- Courier profile repository and service
- JWT authentication filter improvements

## Common Issues

### Port Conflicts

If ports are in use, update `application.yml` in each service or stop conflicting processes.

### Database Connection Errors

Ensure PostgreSQL is running: `docker-compose ps`
Check connection details match `init-multiple-dbs.sh`.

### gRPC Connection Failures

1. Verify Eureka is running and services are registered: `http://localhost:8761`
2. Check service logs for registration errors
3. Ensure `spring.application.name` matches gRPC client address

### Kafka Issues

Kafka takes ~30 seconds to start. Check health: `docker-compose logs kafka`

## Development Guidelines

### Adding a New Service

1. Create module in root `pom.xml`
2. Add dependency on `common` module for gRPC stubs
3. Configure Eureka client in `application.yml`
4. Add database and role in `docker/postgres/init-multiple-dbs.sh`
5. Add route in `api-gateway/src/main/resources/application.yml`
6. Update `start-all.ps1` if using PowerShell script

### Modifying Proto Definitions

1. Edit `.proto` files in `common/src/main/proto/`
2. Rebuild common module: `cd common && mvn clean compile`
3. Rebuild dependent services

### Security Best Practices

- Never commit JWT secrets or database passwords
- Use environment variables for sensitive config
- Validate all user input
- Use parameterized queries (JPA handles this)
- Keep dependencies updated

## Useful Commands

```bash
# View Eureka registered services
curl http://localhost:8761/eureka/apps

# Check API Gateway routes
curl http://localhost:8080/actuator/gateway/routes

# Test authentication
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"password"}'

# Access protected endpoint
curl http://localhost:8080/api/v1/users/me \
  -H "Authorization: Bearer <token>"

# View Kafka topics
docker exec -it courier-kafka kafka-topics --bootstrap-server localhost:9092 --list

# Connect to PostgreSQL
docker exec -it courier-postgres psql -U courier_master -d auth_db
```

## Future Enhancements

- Distributed tracing (Zipkin/Jaeger)
- Centralized configuration (Spring Cloud Config)
- API documentation (OpenAPI/Swagger)
- Load testing and performance optimization
- Kubernetes deployment manifests
- CI/CD pipeline
