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

