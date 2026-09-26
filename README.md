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

