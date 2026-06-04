# Observability

This project includes a local observability stack for development, testing, load testing, and diploma defense.

## Stack

- Spring Boot Actuator exposes service health and Micrometer metrics.
- Prometheus scrapes `/actuator/prometheus` from each backend service.
- Grafana is provisioned with a Prometheus datasource and a starter dashboard.
- OpenTelemetry tracing is documented as optional and is not enabled by default.

Prometheus stores time-series metrics. Grafana visualizes those metrics as dashboards. OpenTelemetry adds distributed traces when a Java agent and collector are configured.

## Run

From the repository root:

```bash
docker compose up --build
```

Useful URLs:

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000
- Grafana login: `admin` / `admin`
- API Gateway health: http://localhost:8080/actuator/health
- API Gateway metrics: http://localhost:8080/actuator/prometheus

Prometheus includes both Docker-network targets such as `api-gateway:8080` and local IDE targets such as `host.docker.internal:8080`. If you run Spring services locally, Prometheus can scrape the host ports from inside Docker. If you run the full Docker stack, the Docker service-name targets are used.

## Main Metrics

HTTP and JVM metrics come from Spring Boot Micrometer:

- `http_server_requests_seconds_count`
- `http_server_requests_seconds_bucket`
- `jvm_memory_used_bytes`
- `process_cpu_usage`
- `hikaricp_connections_active`
- `hikaricp_connections_idle`
- `hikaricp_connections_max`

Courier assignment metrics are emitted by `logistics-service`:

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

Labels are intentionally low-cardinality. Metrics do not include `orderId`, `assignmentId`, `courierId`, `userId`, email, phone, tokens, or secrets.

Kafka and database metrics may appear automatically when the corresponding Spring Boot binders and clients are active.

## Example PromQL

Request rate:

```promql
sum by (application) (rate(http_server_requests_seconds_count[5m]))
```

HTTP p95 latency:

```promql
histogram_quantile(0.95, sum by (application, le) (rate(http_server_requests_seconds_bucket[5m])))
```

HTTP error rate:

```promql
sum by (application, status) (rate(http_server_requests_seconds_count{status=~"4..|5.."}[5m]))
```

Assignment attempts:

```promql
sum by (serviceType, assignmentPolicy) (rate(courier_assignment_attempts_total[5m]))
```

Assignment success rate:

```promql
sum(rate(courier_assignment_success_total[5m]))
```

Assignment p95 duration:

```promql
histogram_quantile(0.95, sum by (le, result) (rate(courier_assignment_duration_seconds_bucket[5m])))
```

## Dashboard

Grafana automatically loads `Courier Platform Observability`.

The dashboard includes:

- HTTP request rate by service
- HTTP p95 latency by service
- HTTP 4xx/5xx error rate
- JVM memory usage
- process CPU usage
- Hikari database connection pool usage
- courier assignment attempts
- courier assignment success/failure rate
- courier assignment duration p95
- pending contractor offers
- timed-out offers
- manual-required assignments
- active assignments and routes

## Load Testing Use

During a load or scenario test:

1. Start the stack with Docker Compose.
2. Open Grafana and the observability dashboard.
3. Run the scenario that creates orders, marks orders ready, assigns couriers, rejects offers, and lets offers time out.
4. Watch assignment attempts, success/failure rates, pending offers, manual-required assignments, and cleanup totals.
5. Use Prometheus targets to confirm every service is being scraped.

This gives a practical story for defense: request load, JVM/resource pressure, database pool pressure, assignment engine throughput, and operational lifecycle events are visible in one place.

## OpenTelemetry

OpenTelemetry Java Agent support is optional and documented rather than enabled in Compose. The agent jar is a large binary and mounting a missing file would break `docker compose up`.

To add it later:

1. Download the OpenTelemetry Java agent from the official OpenTelemetry releases.
2. Place it at `monitoring/otel/opentelemetry-javaagent.jar`.
3. Add an OpenTelemetry Collector and Tempo service.
4. Mount the agent into Java services and set:

```text
JAVA_TOOL_OPTIONS=-javaagent:/otel/opentelemetry-javaagent.jar
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
OTEL_TRACES_EXPORTER=otlp
OTEL_METRICS_EXPORTER=none
OTEL_LOGS_EXPORTER=none
```

## Limitations

- Grafana starts with a practical starter dashboard, not a full SRE dashboard catalog.
- OpenTelemetry tracing is not enabled by default.
- Prometheus target status must be checked after containers are running.
- Custom assignment metrics appear after running assignment scenarios.
- Actuator exposes only `health`, `info`, `metrics`, and `prometheus`; sensitive actuator endpoints are not exposed.
