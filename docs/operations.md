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

