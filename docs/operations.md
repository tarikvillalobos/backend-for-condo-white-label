# Operations

## Configuration and deployment

Use Java 21, a PostgreSQL JDBC `DATABASE_URL`, `DATABASE_USER`, and
`DATABASE_PASSWORD` in production. Set `APP_ENV=production`, `HOST=0.0.0.0`, and
the SMTP settings in [Identity](identity.md). Production startup requires
PostgreSQL, a database password, and authenticated SMTP with STARTTLS.
Secrets belong in the deployment secret store or process environment.

`./gradlew installDist` produces `build/install/community-api/bin/community-api`.
The Dockerfile builds the same distribution and runs it as UID 10001. Terminate
with SIGTERM for Ktor shutdown. Terminate TLS at a trusted reverse proxy; the API
does not trust forwarded address headers for rate limiting by default. An
unconfigured proxy therefore shares one authentication rate limit bucket.

The included Compose stack is for local development. It starts PostgreSQL, the
API, and Mailpit, which captures outgoing authentication email. Open Mailpit at
`http://localhost:8025`. No email is delivered to real recipients by this stack.

```sh
export DATABASE_PASSWORD="$(openssl rand -hex 24)"
docker compose up -d --build
```

Export `BOOTSTRAP_CLIENT_NAME`, `BOOTSTRAP_EMAIL`, and `BOOTSTRAP_PASSWORD`, then:

```sh
docker compose run --rm -e BOOTSTRAP_CLIENT_NAME -e BOOTSTRAP_EMAIL -e BOOTSTRAP_PASSWORD api bootstrap
```

Keep the database password in your local secret store for future Compose runs.
Changing this environment variable does not change an existing PostgreSQL user's
password. Compose's built-in `.env` loading is separate from the native server,
which reads only the process environment.

## Client lifecycle

`bootstrap` creates a new client and administrator, prints their identifiers,
and never overwrites existing accounts. Client-wide administrator memberships
cannot expire. Sensitive permission changes require verification within ten
minutes; authenticate again or call `POST /api/v1/me/verify`.

Suspending a client is an operator action with database access, outside tenant
sessions. Export `CLIENT_ID` and `CLIENT_ACTIVE=false` or `true`, then execute:

```sh
./gradlew run --args=client-state
```

Suspension revokes current credentials and records an audit event. Reactivation
requires users to authenticate again. A client administrator can restore a
disabled location through its normal update endpoint.

## Persistence and consistency

Flyway applies versioned SQL migrations on startup. Data uses indexed,
tenant-scoped relational envelopes with typed JSON payloads. A database row lock
serializes transactions across processes, preserving pickup and reservation
invariants. This deliberately limits throughput; measure load before increasing
scale and replace it with narrower locks and constraints alongside regression
tests. H2 is for local development/tests. The CI PostgreSQL service checks actual
migrations, persistence, rollback, and concurrent updates.

## Backups and recovery

Use scheduled PostgreSQL backups and point-in-time recovery appropriate to the
deployment. Back up all application tables, including Flyway history. Attachments
are stored in the database, so they are included in the same backup. Encrypt
backups and limit access: the private mail queue temporarily contains credentials.

Test restoration into an isolated PostgreSQL instance before relying on backups.
Point the API at the restored instance, start it, and verify `/health/ready`, an
authorized login, and representative records. Never run tests against production.
For H2 local backups, stop the process before copying `data/community.mv.db`.

## Retention and files

Authentication delivery entries are deleted after delivery or credential use;
the mail worker removes expired deliveries. Audit and business records are kept
until a client-specific retention policy is agreed and implemented. There is no
automatic purge of business history. Attachment deletion is explicit and audited.

Uploads accept base64 PNG, JPEG, or PDF, validate size (2 MiB), extension and file
signature, and limit each user to 200 attachments. Downloads require current
access and force attachment disposition with `nosniff`. These checks do not replace
malware scanning; use a scanning provider before enabling untrusted document
distribution in a deployment that requires it.

## Monitoring and limitations

`GET /health/live` checks HTTP responsiveness; `/health/ready` checks database
connectivity and returns 503 when unavailable. Logs carry generated request IDs,
methods, statuses, and sanitized failures. Alert on readiness failures, mail
delivery failures, sustained 5xx responses, and backup failures. Hardware command
routes return 501 until a real provider is configured; verified locker pickup
events are supported through separate integration credentials.

External push/SMS, camera streaming/recordings, and gate/locker opening adapters
still require provider contracts and credentials. Never infer a physical event
