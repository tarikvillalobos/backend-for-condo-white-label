# Community API

Shared monolithic backend for the `smartlocker-app` and `condo-app` white-label applications.

## Overview

Community API will provide the business capabilities used by residents, smart locker users, concierge teams, property managers, and client administrators.

Both applications will share identity, access control, package management, notifications, and other common capabilities. The Condo app will also use condominium-specific features enabled for each client and property.

Smart locker operations must remain usable at standalone locations without requiring a condominium or residential unit.

## Status

**Initial executable foundation.** The Kotlin/Ktor service includes HTTP liveness and readiness endpoints, JSON errors, generated `X-Request-ID` headers, environment configuration, and automated tests. Business endpoints, persistence, authentication, authorization, and provider integrations are not implemented yet.

The capabilities and requirements below describe the planned product scope. Features will be delivered incrementally. Hardware and third-party integrations depend on provider contracts, credentials, and validation.

## Technology Stack

- Kotlin/JVM 2.4.20 with Java 21.
- Ktor 3.6.0 with the Netty server engine.
- Gradle 9.4.1 with Kotlin DSL and the Gradle Wrapper.
- JSON responses through Kotlin serialization.
- Logback 1.6.4 for logging.

The backend will remain a shared monolith for both applications. Business capabilities will be organized into modules as they are implemented.

See [Architecture](docs/architecture.md) for the current layout, planned module boundaries, and delivery order.

## Planned Capabilities

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

