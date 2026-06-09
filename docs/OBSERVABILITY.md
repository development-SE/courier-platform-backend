# Courier Platform Observability

This project includes a local observability stack for development, testing, load testing, and diploma defense.

## What Was Added

- Spring Boot Actuator endpoints for service health and metrics.
- Prometheus scraping for backend services.
- Grafana datasource provisioning for Prometheus.
- A pre-provisioned Grafana dashboard named `Courier Platform Observability`.
- Custom logistics metrics for courier assignment flow.
- Safe lifecycle logs for order, assignment, timeout, and route cleanup events.

## Prometheus, Grafana, And OpenTelemetry

Prometheus collects numeric metrics from `/actuator/prometheus`, such as HTTP request duration, JVM memory, CPU usage, database pool usage, and custom assignment counters.

Grafana visualizes those metrics with dashboards and panels.

OpenTelemetry is for distributed traces across services. It is not enabled by default because the Java agent is a large binary and mounting a missing agent would break Docker Compose. To enable it later, place the agent at:

```text
monitoring/otel/opentelemetry-javaagent.jar
```

Then add the Java agent environment and mounts described in the project notes or OpenTelemetry Java agent documentation.

## Run

```bash
docker compose up --build
```

Useful URLs:

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000
- Grafana login: `admin` / `admin`
- API Gateway health: http://localhost:8080/actuator/health
- API Gateway metrics: http://localhost:8080/actuator/prometheus

Prometheus scrapes services inside the Docker network by service name:

- `api-gateway:8080`
- `auth-service:8081`
- `notification-service:8082`
- `order-service:8083`
- `user-service:8084`
- `company-service:8085`
- `logistics-service:8086`
- `courier-service:8087`
- `eureka-server:8761`

## Main Metrics

Spring Boot and Micrometer metrics:

- `http_server_requests_seconds_count`
- `http_server_requests_seconds_bucket`
- `jvm_memory_used_bytes`
- `process_cpu_usage`
- `hikaricp_connections_active`
- `hikaricp_connections_max`
- Kafka client metrics when available from Spring Kafka clients

Courier assignment metrics:

- `courier_assignment_attempts_total`
- `courier_assignment_success_total`
- `courier_assignment_failed_total`
- `courier_assignment_rejected_total`
- `courier_assignment_timed_out_total`
- `courier_assignment_manual_required_total`
- `courier_assignment_duplicate_prevented_total`
- `courier_assignment_cleanup_total`
- `courier_assignment_duration_seconds`
- `courier_assignment_candidates_found`
- `courier_assignment_candidates_eligible`
- `courier_pending_offers`
- `courier_manual_required_assignments`
- `courier_active_assignments`
- `courier_active_routes`

Custom metrics avoid high-cardinality labels. They do not include `orderId`, `assignmentId`, `courierId`, user IDs, emails, phone numbers, passwords, or tokens.

## Example PromQL

Request rate:

```promql
sum by (application, method, uri) (rate(http_server_requests_seconds_count[5m]))
```

HTTP p95 latency:

```promql
histogram_quantile(0.95, sum by (le, application) (rate(http_server_requests_seconds_bucket[5m])))
```

HTTP 4xx/5xx error rate:

```promql
sum by (application, status) (rate(http_server_requests_seconds_count{status=~"4..|5.."}[5m]))
```

Assignment attempts:

```promql
sum by (serviceType) (rate(courier_assignment_attempts_total[5m]))
```

Assignment success rate:

```promql
sum(rate(courier_assignment_success_total[5m]))
```

Assignment p95 duration:

```promql
histogram_quantile(0.95, sum by (le, serviceType) (rate(courier_assignment_duration_seconds_bucket[5m])))
```

Current pending contractor offers:

```promql
courier_pending_offers
```

## Load Testing Workflow

1. Start the stack with `docker compose up --build`.
2. Open Prometheus targets at http://localhost:9090/targets and confirm services are `UP`.
3. Open Grafana at http://localhost:3000.
4. Open the `Courier Platform Observability` dashboard.
5. Run a scenario that creates orders and triggers assignment.
6. Watch:
   - request rate and latency,
   - 4xx/5xx errors,
   - assignment attempts and successes,
   - manual-required assignments,
   - pending offers and timed-out offers,
   - JVM and database connection pressure.

## Safe Logs

Lifecycle logs include only safe identifiers and state:

- `orderId`
- `assignmentId`
- `courierId`
- `routeId`
- `companyId`
- `status`
- `reason`

Logs must not include:

- raw passwords
- access tokens
- refresh tokens
- Authorization headers
- OTP codes
- confirmation tokens
- secrets

## Known Limitations

- OpenTelemetry tracing is documented but not enabled by default.
- The dashboard is intentionally basic and defense-ready; refine panels during real load testing.
- Prometheus scrape targets use the default internal service ports from Docker Compose.
- If service ports are changed through `.env`, update `monitoring/prometheus/prometheus.yml` as well.
- Order-service and auth-service are still gRPC-first for business APIs; HTTP is enabled for actuator/metrics.