# Community API

Shared monolithic backend for the `smartlocker-app` and `condo-app` white-label applications.

## Overview

Community API provides shared identity, scoped access, and business workflows for residents, smart locker users, concierge teams, property managers, and client administrators.

Both applications use the same identity, package, notification, and access rules. Condominium features are enabled per client and location; a brand selects presentation and application configuration without changing data ownership.

Smart locker operations must remain usable at standalone locations without requiring a condominium or residential unit.

## Status

The Kotlin/Ktor backend includes persistent storage, authentication, authorization,
and working APIs for the modules below. The repository contains tests, deployment
configuration, operational documentation, and an OpenAPI contract. The broader
[Product scope](#product-scope) records requirements and possible extensions;
provider-dependent features and optional workflows are identified separately.

## Implemented capabilities

| Module | Working behavior | Boundary or extension |
| --- | --- | --- |
| Identity | Invitations/activation, password and OTP login, recovery, verified contact changes, profile, rotating sessions, revocation, rate limits | SMTP must be configured for email verification/recovery; SMS login is not integrated |
| Client and location administration | Clients, brands, feature flags, locations, units, owner/tenant/household relationships, scoped roles, account activation, invitations | Cross-client onboarding/state changes use explicit operator CLI commands |
| Deliveries and lockers | Receipt, compartment allocation, history, deadlines, reminders, single-use pickup credentials, delegation, confirmed collection, provider-event deduplication | Physical locker opening needs a vendor adapter and returns 501 |
| Facilities and reservations | Operating hours, local time zones, capacity, availability, maintenance blocks, approval, cancellation, concurrency protection | Waitlists, recurring bookings, and payments are extensions |
| Announcements and events | Scheduled/expiring announcements, unit audiences, attachments, receipts, events linked to reservations, attendance capacity, cancellation | Notification campaigns are an extension |
| Pets | Private pet/unit records, photos, vaccination requirements, species and unit limits, lost notices, resolution | Vaccination document authenticity needs administrative review |
| Requests and incidents | Categories/priorities, ownership, staff-only notes, assignment, deadlines, escalation history, reopen/resolve flows | Automatic escalation and SLA jobs are extensions |
| Visitors and concierge | Unit-scoped invitations, admission credentials, expiry, revocation, single-use rules, staff check-in/out, shift notes | Gate hardware commands require a supported integration |
| Vehicles and parking | Private vehicle records, temporary authorization, space allocation, staff entry/exit history | Physical vehicle-access providers and complex parking policies are separate integrations |
| Staff and maintenance | Staff responsibilities, approved contractor records, equipment, assignments, work orders, status history, evidence | Recurring inspection scheduling is not automated |
| Documents and contacts | Versioned document metadata, audience checks, acknowledgments, published useful contacts | External document providers enforce access to their own URLs |
| Attachments | Authorized PNG/JPEG/PDF upload/download/delete, 2 MiB limit, signatures, ownership and location visibility | Database-backed storage; antivirus and object storage adapters are not included |
| Notifications | Private inbox, unread count, read status, preferences, generic SMTP email, delivery status and retries | Push and SMS need provider adapters; SMTP acceptance does not confirm reading |
| Cameras | Camera metadata, enabled state, unit audience, separate live/recording permissions | Live sessions and recordings return 501 until a real provider is configured |
| Reporting and audit | Permission-filtered counts, statuses, CSV exports, recent-authentication checks, audit records | External analytics pipelines and automated retention are operational extensions |

## Technology stack

- Kotlin/JVM 2.4.20, Ktor 3.6.0, Netty, and Java 21.
- Gradle 9.4.1 with Kotlin DSL and the included Gradle Wrapper.
- Kotlin serialization for JSON; opaque bearer sessions with hashed secrets.
- PostgreSQL for production; persistent H2 for local development and H2 for tests.
- HikariCP connection pooling and Flyway schema migrations.
- Eclipse Angus Mail for authentication and notification email; Logback for redacted HTTP logs.

## Documentation

- [Architecture and transaction design](docs/architecture.md)
- [OpenAPI contract](docs/openapi.yaml)
- [Identity, sessions, SMTP, and recovery](docs/identity.md)
- [Packages and smart lockers](docs/deliveries.md)
- [Facilities and reservations](docs/reservations.md)
- [Community and operations endpoints](docs/community.md)

## Product scope

The following sections preserve the full product brief. Use the implementation
matrix above and endpoint documentation to distinguish available workflows from
provider-dependent or future capabilities.


### Authentication and Account Management

Account activation, invitations, login, recovery, verified contact changes, and profile management. Support password-based and one-time-code authentication according to the configured login policy.

Include session expiration, renewal, logout, session revocation, device/session visibility, and additional verification for privileged or sensitive actions. Protect login and recovery flows against repeated attempts and account enumeration.

### Clients, Brands, and Feature Configuration

Manage client organizations, white-label brands, app configuration, support contacts, and available features. Allow configuration by client, property, and application where appropriate.

Keep brand identity separate from data ownership and access permissions. A client may manage multiple properties or standalone locker locations.

### Condominiums, Locations, and Units

Manage properties, buildings, blocks, floors, units, common areas, addresses, contacts, and operating rules.

Support users linked to multiple authorized locations or units, with explicit context selection. Include standalone locations for smart locker deployments outside residential communities.

### Residents and Memberships

Manage residents, owners, tenants, dependents, authorized household members, and their unit relationships. Support invitations, approval, move-in, move-out, and membership expiration or revocation.

Ownership or household membership must not automatically grant administrative privileges or access to every package addressed to that unit.

### Packages and Smart Lockers

Register deliveries received at reception desks or lockers, identify recipients, track status and storage location, and provide package details, history, reminders, and collection deadlines.

Manage lockers, compartments, availability, maintenance status, and provider integrations. Support pickup codes or QR credentials with expiration, revocation, and single-use rules, plus explicitly authorized collection by another person.

Distinguish a resident reporting a pickup from confirmation by authorized staff or a trusted locker event. Handle duplicate or out-of-order integration events without creating duplicate deliveries or collections.

Both apps must use the same package records and business rules when accessing the same authorized context.

### Cameras

Manage camera locations, availability, permitted audiences, and authorized viewing sessions. Provide live-view access through supported camera providers.

Recording access, playback, or footage requests may be enabled when supported and authorized. Treat live viewing and recording access as separate permissions; do not expose equipment administrator credentials to users.

### Pets

Manage pet registration, responsible residents, unit association, photos, identification details, and vaccination documents where required by the property.

Support lost-and-found notices and property-specific pet rules. Restrict access to private pet and owner records according to the user's responsibilities.

### Common Areas and Reservations

Manage bookable facilities, availability, capacity, operating hours, booking limits, maintenance blocks, and property-specific reservation rules.

Support creation, approval when required, confirmation, cancellation, history, and optional waitlists. Prevent conflicting reservations, including simultaneous requests, and respect the property's local time.

### Events and Community Calendar

Publish community events, scheduled activities, maintenance interruptions, and relevant dates. Support audience targeting, attendance registration where needed, and links between events and facility reservations.

### Announcements and Notices

Create, schedule, publish, update, and archive announcements for a property, building, unit, or authorized audience.

Support attachments, priority, expiration, pinned notices, and read acknowledgments when required. Keep delivery status distinct from confirmation that a person has read a notice.

### Requests, Incidents, and Support

Handle resident requests, complaints, incidents, maintenance reports, and support tickets with categories, priority, attachments, responsible teams, status, and response history.

Support assignment, internal notes, deadlines, escalation, resolution, and reopening. Separate staff-only information from resident-visible communication and protect sensitive reports from unrelated users.

### Concierge and Visitor Management

Provide concierge workflows for expected visitors, guests, contractors, delivery personnel, resident contact, arrival approval, and entry/exit records.

Support visitor invitations with validity windows, permitted locations, revocation, and configurable single-use rules. Record who approved or registered each action.

Include authorized pickup verification, delivery handover, shift notes, and operational incident logs. Gate or door commands require explicit permissions and a supported integration; issuing a command must not be treated as proof that physical entry occurred.

### Vehicles and Parking

Manage resident vehicles, authorized visitor vehicles, assigned parking spaces, temporary authorizations, and relevant entry/exit records.

Support configurable parking rules and restrict access to vehicle and owner information. Hardware-dependent access features require the corresponding integration.

### Staff, Contractors, and Maintenance

Manage staff responsibilities, approved service providers, work orders, scheduled maintenance, inspections, and common equipment records.

Support temporary access for contractors, task assignment, completion evidence, and service history. Staff access must be limited to the properties and work they are authorized to handle.

### Documents and Useful Contacts

Publish property rules, manuals, forms, meeting records, and other authorized documents. Support versions, visibility rules, and acknowledgment when needed.

Maintain property contacts, administration contacts, operating hours, and emergency contact information without exposing private resident contact details to unrelated users.

### Notifications and Communication Preferences

Provide an in-app notification inbox, unread counts, notification history, and preferences. Support push notifications and configurable email, SMS, or messaging providers when integrated.

Track delivery attempts and failures. Target the correct client, property, and recipient, and avoid sending access credentials or unnecessary personal information in notification content.

### Administration, Reporting, and Audit

Provide administrative operations for onboarding clients and properties, managing users and memberships, configuring modules, assigning permissions, and maintaining integrations.

Include operational reports for packages, reservations, requests, visits, and maintenance. Exports must respect the same access restrictions as interactive access.

Record relevant administrative actions, permission changes, and sensitive operations with their actor and context, without logging passwords, authentication codes, or pickup credentials.

## Roles and Permissions

Roles are configurable collections of permissions, not unrestricted access to every enabled feature. A user may have different roles in different clients or properties.

Suggested initial role templates:

| Role | Intended responsibilities |
| --- | --- |
| Platform administrator | Platform configuration and client onboarding. Any cross-client support access must be explicitly authorized and audited. |
| Client administrator | Manage the client's brands, properties, staff, settings, and permitted role assignments. |
| Property manager | Manage assigned condominium operations, residents, facilities, announcements, requests, and reports. |
| Concierge | Handle permitted deliveries, visitors, entry/exit records, resident contact, and shift operations. |
| Operational staff | Handle assigned maintenance, inspections, requests, and other explicitly permitted duties. |
| Resident | Access authorized personal and household resources, packages, reservations, pets, visitors, notices, and requests. |
| Auditor or read-only operator | Inspect explicitly permitted records and reports without modifying operational data. |

Visitor credentials and integration accounts are separate from human administrative roles. A visitor invitation must not grant general platform access, and a hardware account must not inherit a resident or administrator session.

Permissions must distinguish actions such as viewing one's own packages, registering a delivery, confirming collection, viewing a camera, managing a reservation, publishing a notice, checking in a visitor, and assigning a role.

Every operation must validate the enabled feature, authorized client/property context, permitted action, and resource-specific access. Knowing a resource identifier, selecting a brand, or hiding a screen in the app is not authorization. Users must not be able to assign privileges beyond their delegated authority.

## White-Label and Data Isolation

The same backend will serve multiple clients and brands without requiring duplicated business logic for each application.

Branding, supported features, communication channels, operational policies, and integrations may vary by deployment. Access must remain isolated by client and constrained by property membership, role, and resource ownership.

Using the same backend does not automatically link accounts across clients or make their data visible between brands. Any cross-client relationship requires an explicit, authorized business rule.

## Data Protection and Operational Requirements

Protect credentials, personal information, attachments, camera access, and collection records. Keep sensitive integration credentials outside applications and source control.

Define retention, archival, deletion, and authorized export rules for each data category. File uploads require size/type restrictions and appropriate validation. Account deactivation and membership removal must revoke the corresponding access.

Include monitoring, backup and recovery procedures, traceable administrative actions, and clear handling of failed integrations. Repeated requests must not create duplicate reservations, invitations, or package operations. Demonstration data and simulated hardware events must never be represented as real operations.

## Optional Extensions

Additional product scope may include assemblies, polls, voting workflows, financial statements and charge visibility, accounting or payment-provider integrations, utility consumption records, and community classifieds.

These extensions require their own business rules and permissions. Full accounting, payment processing, video hosting, and equipment firmware are not assumed to be built into the initial backend.

## Getting started

### Prerequisites

Install Java 21 and expose it through `JAVA_HOME` or `PATH`. Use the included
Gradle Wrapper. The first build downloads dependencies and needs internet access.
PostgreSQL is required when `APP_ENV=production`; development defaults to a local
H2 file under `data/`.

### Build and test

```sh
./gradlew build
./gradlew test
```

`build` compiles the service, runs tests, and creates distributions. The tests
exercise access control, credential lifecycle, state transitions, serialization,
concurrent allocation, and HTTP contracts. No live hardware or mail provider is
needed by the test suite.

### Bootstrap the first client

Set these environment variables through your shell or secret manager before
running the command. Credentials must not be committed:

| Variable | Purpose |
| --- | --- |
| `BOOTSTRAP_CLIENT_NAME` | Display name for the first client |
| `BOOTSTRAP_EMAIL` | Initial client administrator's email |
| `BOOTSTRAP_PASSWORD` | Initial password, 12–256 characters |
| `BOOTSTRAP_CLIENT_ID` | Optional UUID; generated when omitted |

```sh
./gradlew run --args=bootstrap
```

Bootstrap creates a client and administrator in one transaction and prints their
IDs. Keep the client ID for login. Run bootstrap before starting the local H2
server; use the same database environment for both commands. An existing client
ID is rejected without overwriting data. A new bootstrap operation is an explicit
operator action for creating another isolated client.

### Run locally

```sh
./gradlew run
```

The default listener is `http://127.0.0.1:8080`. Log in with
`POST /api/v1/auth/login` and JSON fields `tenantId`, `email`, and `password`.
Use the returned `accessToken` as `Authorization: Bearer <accessToken>`. Tokens
are opaque session credentials, not JWTs. Access expires after 15 minutes;
refresh rotates both tokens and revokes the previous access token.

Use the administrator session to create locations, units, memberships, and
invitations. Domain routes use `/api/v1/locations/{locationId}`. Creation of
packages, reservations, account invitations, and visitor invitations requires
an `Idempotency-Key`; consult each module's retry semantics.

### Runtime configuration

| Variable | Default or requirement |
| --- | --- |
| `HOST` | `127.0.0.1`; use `0.0.0.0` inside containers |
| `PORT` | `8080` |
| `APP_ENV` | `development`; also accepts `test` or `production` |
| `DATABASE_URL` | `jdbc:h2:file:./data/community;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE` |
| `DATABASE_USER` | `sa` for local H2; configure a PostgreSQL application user in production |
| `DATABASE_PASSWORD` | Empty for local H2; required in production |
| `SMTP_HOST`, `SMTP_FROM` | Configure authentication and notification email delivery |
| `SMTP_PORT` | `587` |
| `SMTP_USER`, `SMTP_PASSWORD` | Configure together; required for production SMTP |
| `SMTP_STARTTLS` | `true`; required in production |

Production startup requires PostgreSQL and authenticated SMTP with STARTTLS.
Development can omit SMTP, leaving authentication emails queued in private
storage. [Identity documentation](docs/identity.md) covers worker retries,
credential handling, local SMTP, and the external-delivery boundary.

The application reads process environment variables. `.env.example` is a
reference; Gradle does not automatically load a `.env` file. For PostgreSQL,
set the JDBC URL, application user, and password in the same environment before
bootstrap and server startup. Flyway applies schema migrations at startup.

### Containers

The Docker image runs the Java 21 distribution. Docker Compose includes `api`,
`db` (PostgreSQL 17), and `mail` (Mailpit for local email capture). Export a
`DATABASE_PASSWORD` before starting the development stack:

```sh
docker compose up -d --build
docker compose run --rm \
  -e BOOTSTRAP_CLIENT_NAME -e BOOTSTRAP_EMAIL -e BOOTSTRAP_PASSWORD \
  api bootstrap
```

The bootstrap environment variables must be set in the calling shell. The API
is exposed on loopback port 8080, PostgreSQL on 5432, and Mailpit's development
inbox at `http://127.0.0.1:8025`. This Compose setup uses development mode and
local mail capture. Production requires authenticated STARTTLS SMTP and a
deployment-specific TLS/proxy setup. Persist the PostgreSQL volume and keep
database backups outside the application container.

An operator can suspend or reactivate a client with `CLIENT_ID` and
`CLIENT_ACTIVE=true|false` through `./gradlew run --args=client-state`.
Suspension revokes the client's account credentials; it does not delete records.

### Health endpoints

| Method | Path | Behavior |
| --- | --- | --- |
| `GET` | `/health/live` | 200 with `{"status":"UP"}` when HTTP handling is available |
| `GET` | `/health/ready` | Database connectivity check; 200 UP or 503 DOWN |

```sh
curl http://127.0.0.1:8080/health/live
curl http://127.0.0.1:8080/health/ready
```

Readiness covers the database, not SMTP acceptance or external hardware. Serve
production HTTP behind TLS and configure backups, restore drills, retention,
and monitoring according to the deployment's requirements.

## Commit Guidelines

All contributions must follow these rules:

- Each commit must change exactly one file.
- Each commit must contain at most 20 changed lines, counting additions and deletions together.
- There is no limit on the number of commits. Use as many small commits as needed.
- Split larger changes into multiple commits, including changes to the same file.

For example, 10 added lines and 10 deleted lines reach the 20-line limit. Replacing one line counts as two changed lines: one deletion and one addition.

These limits apply to all commits, including code, tests, documentation, and configuration changes.

## License

Private and proprietary software.
