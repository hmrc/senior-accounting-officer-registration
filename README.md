
# senior-accounting-officer-registration

Backend microservice for Senior Accounting Officer registration.

The service exposes protected registration endpoints and orchestrates sign-up requests with downstream services.

## Running the service

Start the service locally with:

```bash
sbt run
```

By default the service runs on port `9000`.

## Tests

Run the unit tests with:

```bash
sbt test
```

Run formatting and lint checks with:

```bash
sbt lint
```

## Registration APIs

All routes below use the `/senior-accounting-officer-registration` prefix and require authentication and a UUID `correlationId` header.

`POST /sign-up` retains its synchronous behavior. Its controller and service live in `v1` packages; the connectors and shared request models are unchanged.

`POST /v2/sign-up` accepts the existing signup JSON with a required `idempotencyKey` (nonblank, at most 256 characters). It returns `202 {"idempotencyKey":"..."}` after the initial work item and status are persisted. Keys are scoped to the authenticated internal user ID. Repeating the same request returns the same key; changing its payload returns 409 with `IDEMPOTENCY_KEY_CONFLICT`. An enrolled owner can replay an existing request, but cannot start a new registration.

Poll `GET /v2/sign-up/:idempotencyKey` using the same authenticated user:

| Result | HTTP response |
| --- | --- |
| Pending, including transient retries | 204 |
| ETMP, DPS and tax enrolments completed | 200, `{"subscriptionId":"<ETMP ID>"}` |
| Permanent failure or retry exhaustion | Existing `/sign-up` 500/502 `ApiError` mapping |
| Unknown, expired or another user's key | 404, `{"reason":"NOT_FOUND"}` |

### Background processing

Five separate hmrc-mongo work-item collections process ETMP → DPS → tax enrolments, followed by independent email and audit commands. Each command carries its complete state and creates its successors. Email is queued per recipient; audit emits `RegistrationCompleted` through the HMRC audit connector with a stable event ID and completion timestamp. Neither confirmation affects registration success. When auditing is disabled by configuration, the audit command completes without delivery.

Successful operations are checkpointed before status publication and handoff. Unique command keys make handoffs replayable; lease ownership and heartbeats protect work across instances and restarts. Delivery is at least once: a downstream success followed by a crash before its checkpoint can repeat the call. An ETMP `AlreadySubscribed` response remains a permanent error because it does not provide the required ID.

Workers rebuild a correlation-only `HeaderCarrier`, following `senior-accounting-officer`. This relies on the confirmed token-free background access contract for tax enrolments; caller bearer tokens are not persisted.

| `work-items` setting | Default |
| --- | --- |
| `enabled` | `true` |
| `poll-interval-seconds` | `1` |
| `operation-timeout-seconds` | `60` |
| `lease-seconds` | `120` |
| `retry-delays-seconds` | `[60, 300, 900, 3600]` |
| `retention-seconds` | `604800` (seven days) |

Transport errors, timeouts, HTTP 408/429 and 5xx receive five total attempts with the configured delays. Other unsuccessful responses and malformed success bodies fail permanently. Mongo publication/handoff retries do not consume downstream attempts. The operation timeout must be shorter than the lease.

Queue depth and per-step retry/completion/permanent-failure metrics use the `registration` prefix. Logs carry orchestration and correlation IDs. Retention starts only after every command is inactive, including email and audit; all records share an expiry, and expired keys can be reused before Mongo's TTL sweep.

Run integration tests with a local MongoDB on port 27017:

```bash
sbt 'it / test'
sbt checkLint
```

Tests disable automatic polling and drive workers directly. New Mongo suites use isolated databases and cover restart recovery, partial handoffs, retries, ownership, retention, and the routed v2 journey.

## OpenApi Schema

This repository utilises [play-swagger](https://github.com/play-swagger/play-swagger) to generate an open api specification using a code-first approach.

The schema is generated upon running `sbt run`. It can be found in `/target/swagger/swagger.json` from the root of the repository.

### Viewing the Schema

The schema is served from `http://localhost:10059/swagger`.

You can view the docs by:
* running `sm2 -start DEVHUB_PREVIEW_OPENAPI`
* accessing http://localhost:9680/api-documentation/docs/openapi/preview in a web browser
* Providing the URL `http://localhost:10059/swagger` and submitting the form

## License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
