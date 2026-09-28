# Community API

API compartilhada para os aplicativos SmartLocker e Condo, implementada em Kotlin, Ktor e PostgreSQL. O contrato público está em [docs/openapi.yaml](docs/openapi.yaml). A versão publicada usa o prefixo `/v1`.

## Testar com Docker

Configure uma vez `DATABASE_PASSWORD` e `API_ENCRYPTION_KEY` no arquivo `.env` da raiz. Gere a senha com `openssl rand -hex 24` e a chave com `openssl rand -base64 32 | tr '+/' '-_' | tr -d '='`. Guarde os mesmos valores para os próximos reinícios. O arquivo `.env` é ignorado pelo Git.

```sh
docker compose up -d --build
curl http://127.0.0.1:8080/v1/health/ready
```

O Compose inicia PostgreSQL 17, API e Mailpit. O banco e os arquivos enviados ficam em volumes persistentes. A caixa de e-mails de teste fica em [http://127.0.0.1:8025](http://127.0.0.1:8025).

Crie a primeira marca e o administrador informando as variáveis abaixo:

```sh
export BOOTSTRAP_CLIENT_NAME='Meu condomínio'
export BOOTSTRAP_EMAIL='admin@example.test'
export BOOTSTRAP_PASSWORD='uma-senha-forte-de-teste'
docker compose run --rm -e BOOTSTRAP_CLIENT_NAME -e BOOTSTRAP_EMAIL -e BOOTSTRAP_PASSWORD api bootstrap
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
