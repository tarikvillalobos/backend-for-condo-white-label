# Architecture

## Decision: Kotlin with Ktor

Community API is a Kotlin/JVM modular monolith served by Ktor and Netty. Both
applications use the same API and business rules. Gradle Kotlin DSL manages the
build, Kotlin serialization handles JSON, and the JVM toolchain targets Java 21.

The initial executable contains application startup, environment validation,
HTTP error handling, request correlation, and health endpoints. Business modules,
authentication, persistent storage, and provider integrations are planned.

## Current source layout

```text
src/main/kotlin/com/community/api/
  Application.kt       # Composition and server startup
  config/              # Environment configuration
  health/              # Liveness and readiness routes
  plugins/             # JSON, errors, request IDs, HTTP logging
src/main/resources/     # Runtime logging configuration
src/test/kotlin/        # HTTP and configuration tests
```

## Planned business boundaries

Add packages for each capability as its first use case is implemented:

| Module | Responsibilities |
| --- | --- |
| Identity and access | Accounts, sessions, invitations, scoped permission grants |
| Clients and brands | Client ownership, branding, app and feature configuration |
| Locations and memberships | Condominiums, standalone locations, units, user relationships |
| Deliveries and lockers | Shared package records, pickup authorization, locker operations |
| Community | Pets, facilities, reservations, calendar, announcements |
| Operations | Requests, visitors, vehicles, parking, staff, maintenance |
| Content and communication | Documents, contacts, inbox, delivery preferences |
| Administration | Onboarding, reports, exports, audit records |
| Integrations | Provider adapters for lockers, cameras, access control, notifications |

Within each module, routes translate HTTP requests, application services execute
use cases, domain code enforces business rules, and repositories/adapters isolate
storage and providers. Keep cross-module interactions explicit through services.
Introduce infrastructure when a use case needs it; no database or simulated
hardware is connected in this foundation.

## Authorization requirements for the first business endpoint

Resolve the authenticated actor and active grants from trusted server-side state.
Validate client, authorized location, enabled feature, action permission, and
resource ownership for every operation. A supplied client ID, brand, unit ID, or
role name cannot establish authorization. Repository queries and exports must
apply the same restrictions. Session and membership revocation must take effect
on subsequent requests.

A standalone locker location belongs to a client and does not require a
condominium or unit. Household relationships do not grant permission to collect
another person's package. Integration accounts and visitor credentials require
separate authentication and narrowly scoped permissions.

