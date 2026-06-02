# Security and Lifecycle Notes

## REST Error Format

Gateway errors use a standard safe response body:

```json
{
  "timestamp": "2026-06-01T12:00:00Z",
  "status": 400,
  "error": "BAD_REQUEST",
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "path": "/api/v1/orders",
  "traceId": "correlation-id",
  "fieldErrors": []
}
```

Stack traces, SQL details, token values, secrets, OTP codes, and internal class names must not be returned to clients.

## Validation Errors

Validation failures return HTTP 400 with code `VALIDATION_ERROR`. Field-level errors use:

```json
{
  "field": "latitude",
  "message": "must be between -90 and 90"
}
```

Coordinates should be validated as:

- latitude: `-90..90`
- longitude: `-180..180`

## gRPC to HTTP Mapping

Gateway REST facades should map gRPC/domain failures to HTTP status codes:

- `INVALID_ARGUMENT` -> 400
- `UNAUTHENTICATED` -> 401
- `PERMISSION_DENIED` -> 403
- `NOT_FOUND` -> 404
- `ALREADY_EXISTS` -> 409
- `FAILED_PRECONDITION` -> 409
- `ABORTED` -> 409
- `UNAVAILABLE` -> 503
- `DEADLINE_EXCEEDED` -> 504
- `INTERNAL` -> 500

Existing gRPC contracts that already carry `common.v1.Response` should keep using that response for business-level failures.

## Correlation ID

The gateway accepts `X-Correlation-Id` from incoming requests. If it is absent or unsafe, the gateway generates a UUID.

The correlation ID is:

- added to response header `X-Correlation-Id`,
- added to gateway request attributes,
- added to MDC as `correlationId`,
- included in standard REST error responses as `traceId`,
- forwarded through gateway routes via `X-Correlation-Id`.

This is intentionally simple correlation support, not a distributed tracing rewrite.

## Kafka Diagnostics

Kafka consumers must not log full payloads. Log safe context instead:

- topic,
- key if available,
- partition and offset if available,
- event type,
- orderId,
- assignmentId,
- courierId,
- exception class,
- safe message.

Retryable failures may use existing retry behavior. Non-retryable business failures should be logged and skipped safely where the existing flow allows. Do not add DLQ infrastructure unless a service already has it.

## Scheduler Diagnostics

Schedulers should keep item-level transaction behavior when it is safer than a whole-batch transaction.

For offer timeouts, each assignment is processed independently. Batch logs should include:

- scanned count,
- processed count,
- skipped count,
- failed count.

One failed timeout item must not stop the rest of the batch.

## Logging Safety

Use SLF4J. Never log:

- raw passwords,
- access tokens,
- refresh tokens,
- Authorization headers,
- OTP codes,
- confirmation tokens,
- secrets,
- full request bodies containing sensitive data,
- full Kafka payloads.

Prefer safe identifiers such as `userId`, `orderId`, `courierId`, `assignmentId`, `routeId`, and `companyId`.

## Common Error Codes

Common codes used across the platform include:

- `AUTH_REFRESH_TOKEN_REUSED`
- `AUTH_REFRESH_TOKEN_EXPIRED`
- `AUTH_FORBIDDEN`
- `ORDER_NOT_FOUND`
- `ORDER_INVALID_STATUS_TRANSITION`
- `ORDER_ACCESS_DENIED`
- `ASSIGNMENT_NOT_FOUND`
- `ASSIGNMENT_DUPLICATE_ACTIVE`
- `ASSIGNMENT_INVALID_STATUS`
- `ASSIGNMENT_ACCESS_DENIED`
- `ROUTE_CLEANUP_ALREADY_DONE`
- `ROUTE_CLEANUP_FAILED`
- `COURIER_NOT_ELIGIBLE`
- `COMPANY_SCOPE_VIOLATION`

## Debugging Assignment and Order Flows

To debug failed assignment/order flows, search logs by `X-Correlation-Id` for REST-originated requests, then follow safe identifiers:

- `orderId` for order state changes and order events,
- `assignmentId` for assignment lifecycle changes,
- `courierId` for courier eligibility and capacity decisions,
- `routeId` for route cleanup and capacity release.

For Kafka-originated flows, use topic, partition, offset, event type, and entity IDs from the consumer logs.
