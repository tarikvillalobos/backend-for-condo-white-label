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
