# Operação da Community API

## Subir localmente

Instale e inicie o Docker Desktop. Na raiz do projeto, crie `.env` com `DATABASE_PASSWORD` e `API_ENCRYPTION_KEY`. A senha pode ser gerada com `openssl rand -hex 24`; a chave precisa codificar 32 bytes em Base64 URL, por exemplo `openssl rand -base64 32 | tr '+/' '-_' | tr -d '='`. Guarde os mesmos valores para os reinícios: trocar a senha não redefine o usuário do PostgreSQL já criado, e trocar a chave torna dados cifrados ilegíveis.

```sh
docker compose up -d --build
docker compose ps
curl http://127.0.0.1:8080/v1/health/ready
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
cannot expire. Sensitive permission changes require verification within ten
minutes; authenticate again or call `POST /api/v1/me/verify`.

Suspending a client is an operator action with database access, outside tenant
sessions. Export `CLIENT_ID` and `CLIENT_ACTIVE=false` or `true`, then execute:

```sh
./gradlew run --args=client-state
```

Suspension revokes current credentials and records an audit event. Reactivation
requires users to authenticate again. A client administrator can restore a
disabled location through its normal update endpoint.

## Persistence and consistency

Flyway applies versioned SQL migrations on startup. Data uses indexed,
tenant-scoped relational envelopes with typed JSON payloads. A database row lock
serializes transactions across processes, preserving pickup and reservation
invariants. This deliberately limits throughput; measure load before increasing
scale and replace it with narrower locks and constraints alongside regression
tests. H2 is for local development/tests. The CI PostgreSQL service checks actual
migrations, persistence, rollback, and concurrent updates.

## Backups and recovery

Use scheduled PostgreSQL backups and point-in-time recovery appropriate to the
deployment. Back up all application tables, including Flyway history. Attachments
are stored in the database, so they are included in the same backup. Encrypt
backups and limit access: the private mail queue temporarily contains credentials.

Test restoration into an isolated PostgreSQL instance before relying on backups.
Point the API at the restored instance, start it, and verify `/health/ready`, an
authorized login, and representative records. Never run tests against production.
For H2 local backups, stop the process before copying `data/community.mv.db`.

## Retention and files

Authentication delivery entries are deleted after delivery or credential use;
the mail worker removes expired deliveries. Audit and business records are kept
until a client-specific retention policy is agreed and implemented. There is no
automatic purge of business history. Attachment deletion is explicit and audited.

Uploads accept base64 PNG, JPEG, or PDF, validate size (2 MiB), extension and file
signature, and limit each user to 200 attachments. Downloads require current
access and force attachment disposition with `nosniff`. These checks do not replace
malware scanning; use a scanning provider before enabling untrusted document
distribution in a deployment that requires it.

## Monitoring and limitations

`GET /health/live` checks HTTP responsiveness; `/health/ready` checks database
connectivity and returns 503 when unavailable. Logs carry generated request IDs,
methods, statuses, and sanitized failures. Alert on readiness failures, mail
delivery failures, sustained 5xx responses, and backup failures. Hardware command
routes return 501 until a real provider is configured; verified locker pickup
events are supported through separate integration credentials.

External push/SMS, camera streaming/recordings, and gate/locker opening adapters
still require provider contracts and credentials. Never infer a physical event
from a command request. Optional financial, voting, waitlist, and recurrence
features remain outside this implementation.

## Verification

Run `./gradlew build installDist` and `python3 scripts/smoke_test.py` for local
tests and a complete HTTP journey with restart persistence. The smoke test uses
an isolated temporary database and generated credentials, then removes them.

The OpenAPI validator also checks that every implemented route is documented:

```sh
python3 -m venv build/spec-venv
build/spec-venv/bin/pip install -r requirements-dev.txt
build/spec-venv/bin/python scripts/check_openapi.py
```

CI runs these checks, PostgreSQL integration tests, a container build, and the
commit-history policy. Python 3.9 or later is needed for the helper scripts.

## Commit policy

Use `python3 scripts/small_commits.py FILE... --push` to split text changes into
commits of at most 20 added/deleted lines, one file per commit on `main`.
`python3 scripts/check_commits.py` verifies the entire history, including in CI.
Binary Gradle Wrapper JAR changes occupy a single-file commit; Git reports no
textual line count for binary artifacts. Intermediate small commits may not build;
push after validating each complete batch. Never force-push to resolve a diverged
remote automatically.
