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

Accounts belong to one client. Authentication uses opaque access and refresh
tokens with random secrets and stored hashes, rather than signed JWT claims.
Every request reloads session/account/client state, so revocation takes effect
without waiting for token expiry. Access lasts 15 minutes; refresh lasts at most
30 days, rotates on use, and detects replay. Password hashes use PBKDF2-HMAC-SHA256
with individual salts and 600,000 iterations.

Invitations, recovery, OTP, and verified contact changes have expiring, bounded,
single-use challenges. Sensitive administration requires recent password
verification. Rate limits are durable and scoped to account and connection
source; forwarding headers are not trusted as authentication or network identity.

Authentication email uses a transactional private outbox and SMTP worker.
The worker claims deliveries under a lease and releases the database transaction
before network I/O. SMTP acceptance and user receipt remain distinct. A crash
after SMTP acceptance may repeat an email, so delivery is at least once.
Provider errors are reduced to safe status information. Pending delivery records
contain short-lived secrets and require protected storage and backup access.

See [Identity API](identity.md) for refresh, credential, recovery, and SMTP details.

## Authorization and white-label isolation

An authenticated actor establishes the client; request payloads cannot switch it.
Each protected operation checks the active client, account, membership, selected
location, enabled feature, and action permission. Location/resource ownership
and field-level restrictions are checked inside the same transaction as writes.
The same resource restrictions apply to reports and exports.

Memberships may be scoped to a location and unit, or deliberately client-wide.
Roles are permission sets; custom roles and direct grants are constrained by the
assigning administrator's authority. Client administrators have explicit
client-wide privileges. Other roles remain scoped to their memberships.
Unit membership alone never grants collection rights for another person's parcel.
Owner, tenant, dependent, and household classifications describe a unit relationship;
they do not add role permissions. Location policies can require vaccination
references, restrict species, and limit registered pets per unit.

Brands select presentation and application configuration. They do not establish
tenant ownership or bypass permissions. Standalone locker locations belong to a
client but need neither a condominium nor a unit.

Resident lists filter by owner or audience. Staff-only request comments are
filtered separately from resident-visible history. Public lost-pet notices omit
private owner/unit/vaccination data. Inbox entries cease to be visible when their
location membership or feature access is revoked. Uploaded private attachments
require ownership or an explicit read-all grant; published attachments require
location document access.

## Domain consistency

- Package reports do not confirm physical pickup. Authorized staff or validated
  integration events must confirm collection and consume the pickup credential.
- Package receipt, reservations, account invitations, and visitor invitations
  require idempotency keys. Read the module contracts for return/replay semantics;
  one-time invitation credentials are never reissued on a duplicate request.
- Locker provider events have dedicated credentials, binding to configured
  integrations, event deduplication, timestamp checks, and state validation.
- Reservations use facility time zones, operating rules, and half-open intervals.
  Pending approvals reserve capacity, preventing approval-time overbooking.
- Event attendance, document acknowledgments, notice receipts, and inbox read
  status avoid duplicate records for repeated actions.
- Visitor admissions require the staff action, a valid credential, an active
  inviter, the allowed time window, and the configured single-use rule.
- Work orders, request status changes, vehicle movements, and administrative
  changes retain audit/history records under the same tenant/location boundary.

## Provider and storage boundaries

Authentication email has a real SMTP adapter. In-app notifications are persisted
independently of external push/SMS/email delivery. Camera live view/recording,
physical locker opening, and other hardware commands require real provider
adapters and credentials; unsupported operations return an explicit 501.
The integration event contract accepts trusted provider events but does not
simulate hardware or claim that a physical action occurred.

Managed attachments are stored in the database with a 2 MiB decoded limit,
PNG/JPEG/PDF signature checks, ownership, and download authorization. This is
suitable for small files; larger deployments should add object storage, scanning,
retention, and tested authorization-preserving download adapters. Domain metadata
may also reference HTTPS documents hosted elsewhere; those providers enforce
their URL access policies independently.

## Runtime and operations

`/health/live` confirms HTTP handling. `/health/ready` checks the database and
returns 503 when unavailable. It does not certify SMTP or hardware availability.
The default listener is `127.0.0.1`; containers use an explicit `HOST=0.0.0.0`.
Production configuration requires PostgreSQL credentials and authenticated SMTP
with STARTTLS. TLS termination, database encryption/backups, restore drills,
monitoring, and retention must be configured for the deployment.

HTTP logs contain method, status, and a generated request ID. Errors return a
stable code and safe message with that ID. Unexpected-error logs omit exception
messages that might contain secrets. Request bodies, credentials, and private
records are not written to HTTP logs.

Bootstrap runs as an operator CLI command and creates a client administrator in
one transaction. The client-state CLI can disable a client and revoke associated
credentials. Application HTTP endpoints do not expose unrestricted cross-client
administration. Shutdown cancels managed worker coroutines and closes the pool.

The [README](../README.md) maps working modules to the broader product scope.
The [OpenAPI contract](openapi.yaml) lists routes and request models; the module
guides explain domain rules and external-provider boundaries.
