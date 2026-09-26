# Architecture

## Kotlin and Ktor modular monolith

Community API serves both white-label applications from one Kotlin/JVM service.
Ktor/Netty handles HTTP, Kotlin serialization defines typed JSON contracts, and
Gradle targets Java 21. Module boundaries group related routes and business rules
without requiring separate services or duplicated package records.

```text
src/main/kotlin/com/community/api/
  Application.kt       # Server composition, CLI dispatch, SMTP worker lifecycle
  config/              # Listener and environment validation
  core/                # Database transactions, permissions, audit, integrations
  identity/            # Accounts, sessions, challenges, delivery, rate limiting
  platform/            # Clients, brands, locations, units, memberships, reports
  deliveries/          # Packages, lockers, delegation, pickup, provider events
  reservations/        # Facilities, local-time rules, booking and maintenance
  community/           # Community, visitor, content, vehicle, maintenance flows
  health/              # HTTP liveness and database readiness
  plugins/             # JSON, body limits, errors, correlation, redacted logging
src/main/resources/db/migration/  # Flyway schema migrations
src/test/kotlin/                   # Domain, HTTP, isolation, concurrency tests
```

## Persistence and transaction boundaries

PostgreSQL is the production store. Development defaults to a persistent H2 file;
tests use isolated in-memory H2 databases in PostgreSQL compatibility mode.
HikariCP owns connections, and Flyway applies the migration history.

Records have an envelope containing `id`, `kind`, `tenantId`, `locationId`,
`ownerId`, typed JSON `data`, creation/update timestamps, and a version number.
The database stores the envelope in indexed columns and JSON payloads as text.
This keeps common tenancy, ownership, audit, and migration handling consistent
while each module uses typed domain models and explicit transitions.

`Database.query` runs blocking JDBC work on the IO dispatcher. Every transaction
takes a database row lock on `app_mutex`. It serializes reads and writes across
API instances, so capacity checks and writes cannot race. Package allocation,
single-use credentials, idempotency guards, booking overlap checks, authorization,
and audit changes commit or roll back together. Version comparisons add stale
record protection when updating or deleting records.

This deliberately favors simple consistency over throughput. A busy deployment
must replace the global lock with tested location/resource locks and appropriate
database constraints before expecting high write concurrency. The current JSON
store also loads records before pagination and domain filtering; large datasets
need dedicated indexes, SQL queries, and possibly per-module relational tables.
There are no database foreign keys between JSON payload references. Domain
services validate these references inside the transaction; direct database writes
must not bypass those rules.

## Identity and sessions

A standalone locker location belongs to a client and does not require a
condominium or unit. Household relationships do not grant permission to collect
another person's package. Integration accounts and visitor credentials require
separate authentication and narrowly scoped permissions.

## Consistency requirements

Use durable transactions and database constraints for concurrent reservations,
single-use credentials, and idempotent operations. Provider events need a stable
deduplication key and validated state transitions for delayed or out-of-order
delivery. User-reported pickup and trusted collection confirmation are distinct
events. Notification delivery and read acknowledgment are also distinct.

## Runtime behavior

`/health/live` confirms that HTTP requests can be handled. `/health/ready` currently
confirms that the foundation initialized; it has no external dependencies to
probe. Add bounded dependency checks and return HTTP 503 on failure when storage
or other required dependencies are introduced.

HTTP logs contain method, status, and a generated request ID. Handled errors
(400, 404, 415, and 500) contain a stable code, safe message, and the same request ID. Unexpected error
logs omit exception messages, which may contain credentials or personal data.
Add an appropriately redacted diagnostics sink when implementing business flows.

The default listener is local (`127.0.0.1`). Configure `HOST` explicitly for a
container or server. This foundation has no authentication or business data;
production deployment requires the authorization, persistence, integration,
retention, monitoring, and recovery work described in the README.

## Delivery order

1. Trusted identity, revocable sessions, client/location context, and permission checks.
2. Persistent client, location, membership, and package records with audit trails.
3. Package receipt and authorized collection shared by both apps, then provider adapters.
4. Condominium modules, each with its access rules, transaction guarantees, and tests.

The full planned product scope remains in [README.md](../README.md).

## Framework references

- [Ktor server configuration](https://ktor.io/docs/server-configuration-code.html)
- [Ktor application testing](https://ktor.io/docs/server-testing.html)
- [Kotlin Gradle configuration](https://kotlinlang.org/docs/gradle-configure-project.html)
