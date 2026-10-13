
# senior-accounting-officer-registration

Backend microservice for Senior Accounting Officer registration.

The service exposes protected registration endpoints and orchestrates sign-up requests with downstream services. It orchestrates downstream requests to:

1. ETMP
1. DPS Subscription Write
1. EACD
1. email (for the confirmation email)
1. explicit audit

## Running the service

Start the service locally with:

```bash
sbt run
```

By default the service runs on port `10059`. In development, it proxies requests to `senior-accounting-officer-stubs`, which is expected on port `10061`.

## Endpoints

### sign-up

```text
POST /senior-accounting-officer-registration/sign-up
```

accepts a JSON request body and orchestrates the required work. See Bruno collection for examples.

## Tests

Run the tests with sbt.

```bash
sbt test    # unit tests
sbt it/test # integration tests
sbt lint    # formatting/linting
```

## Registration APIs

All routes below use the `/senior-accounting-officer-registration` prefix and require authentication and a UUID `correlationId` header.

`POST /sign-up` retains its synchronous behaviour. Its controller and service live in `v1` packages; the connectors and shared request models are unchanged.

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

Tax enrolments requires the caller's bearer token. The v2 work item stores the Authorization header in MongoDB and clears it from all work items when tax enrolment succeeds or fails permanently. Email and audit commands do not carry it. The token must remain valid through any retries. Access to the work-item collections must be restricted accordingly.

Successful operations are checkpointed before status publication and handoff. Unique command keys make handoffs replayable, lease ownership and heartbeats protect work across instances and restarts. Delivery is at least once: a downstream success followed by a crash before its checkpoint can repeat the call. An ETMP `AlreadySubscribed` response remains a permanent error because it does not provide the required ID.

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

## Running tests with Bruno

Users can import the collection into Bruno and, once the required environment setup is complete, run the protected service requests directly.

### What is Bruno?

Bruno is an open-source API client used for manual exploratory testing and cross-team collaboration. It allows you to create, manage and run HTTP requests against a service directly from a collection stored in a repository.

### Pre-requisites:
* Download and install Bruno.
* Start the needed services in a terminal session with the following command:

```bash
sm2 --start SAO_ALL
```

### Running Tests in the Local environment

* Open Bruno.
* From the Bruno menu select Open Collection.
* Navigate to the bruno folder in the repository.
* Click the Open button.
* In Bruno, select the local environment from the environment dropdown.
* Select a request from the collection in the left-hand panel.
* Click Send to execute the request.
* Verify the response status and body match the expected output.
## License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
