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
