# Arquitetura da Community API

A API é um monólito Kotlin/Ktor para SmartLocker e Condo. A aplicação pública registra as rotas do [OpenAPI v1](openapi.yaml) em `src/main/kotlin/com/community/api/v1/`. Os pacotes `identity`, `platform`, `deliveries`, `reservations` e `community` concentram os fluxos; `core` contém banco e infraestrutura compartilhada. O ponto de entrada é `Application.kt`.

## Persistência e isolamento

PostgreSQL 17 é o banco de produção. Flyway aplica as migrações em `src/main/resources/db/migration/` e `db/postgresql/`; H2 em modo compatível é usado nos testes e no smoke test da distribuição. `app_records` armazena os registros de domínio com `tenant_id`, `location_id`, proprietário, versão e payload JSON. `V1Store` verifica tenant e marca em leituras e alterações. A marca é resolvida a partir de `X-Brand-Id`, mas o cabeçalho não concede permissão.

As rotas v1 usam transações com locks por escopo, controle de versão, ETag e chaves de idempotência. Listas principais usam versões históricas, índices e cursores assinados com snapshot de 15 minutos. Coleções derivadas pequenas são materializadas com limite de 5.000 itens; filtros mais estreitos são necessários acima desse limite. Ajuste `DB_POOL_SIZE` junto com o número de réplicas e a capacidade do PostgreSQL.

## Autorização e auditoria

Sessões de morador, sessões de equipe e chaves de dispositivo têm credenciais distintas. O servidor combina permissões, contexto ativo, escopo do condomínio e módulos habilitados. Operações sensíveis exigem verificação recente da identidade quando o OpenAPI declara `x-step-up`.

As rotas de negócio registram requisições; operações auditadas registram eventos e alterações. O log de eventos tem cadeia de hashes por marca ou condomínio. A verificação de integridade está exposta às permissões administrativas indicadas no contrato. A cadeia ajuda a detectar mudanças indevidas, mas a segurança operacional também depende de restringir acesso ao banco e proteger backups externos.

`Database.query` runs blocking JDBC work on the IO dispatcher. Every transaction
takes a database row lock on `app_mutex`. It serializes reads and writes across
API instances, so capacity checks and writes cannot race. Package allocation,
single-use credentials, idempotency guards, booking overlap checks, authorization,
and audit changes commit or roll back together. Version comparisons add stale
record protection when updating or deleting records.

This deliberately favors simple consistency over throughput. A busy deployment
must replace the global lock with tested location/resource locks and appropriate
database constraints before expecting high write concurrency. The current JSON
store also loads records before pagination and domain filtering; large datasets
need dedicated indexes, SQL queries, and possibly per-module relational tables.
There are no database foreign keys between JSON payload references. Domain
services validate these references inside the transaction; direct database writes
must not bypass those rules.

## Identity and sessions

Accounts belong to one client. Authentication uses opaque access and refresh
tokens with random secrets and stored hashes, rather than signed JWT claims.
Every request reloads session/account/client state, so revocation takes effect
without waiting for token expiry. Access lasts 15 minutes; refresh lasts at most
30 days, rotates on use, and detects replay. Password hashes use PBKDF2-HMAC-SHA256
with individual salts and 600,000 iterations.

Invitations, recovery, OTP, and verified contact changes have expiring, bounded,
single-use challenges. Sensitive administration requires recent password
verification. Rate limits are durable and scoped to account and connection
source; forwarding headers are not trusted as authentication or network identity.

Authentication email uses a transactional private outbox and SMTP worker.
The worker claims deliveries under a lease and releases the database transaction
before network I/O. SMTP acceptance and user receipt remain distinct. A crash
after SMTP acceptance may repeat an email, so delivery is at least once.
Provider errors are reduced to safe status information. Pending delivery records
contain short-lived secrets and require protected storage and backup access.

See [Identity API](identity.md) for refresh, credential, recovery, and SMTP details.

## Authorization and white-label isolation

An authenticated actor establishes the client; request payloads cannot switch it.
Each protected operation checks the active client, account, membership, selected
location, enabled feature, and action permission. Location/resource ownership
and field-level restrictions are checked inside the same transaction as writes.
The same resource restrictions apply to reports and exports.

Memberships may be scoped to a location and unit, or deliberately client-wide.
Roles are permission sets; custom roles and direct grants are constrained by the
assigning administrator's authority. Client administrators have explicit
client-wide privileges. Other roles remain scoped to their memberships.
Unit membership alone never grants collection rights for another person's parcel.
Owner, tenant, dependent, and household classifications describe a unit relationship;
they do not add role permissions. Location policies can require vaccination
references, restrict species, and limit registered pets per unit.

Brands select presentation and application configuration. They do not establish
tenant ownership or bypass permissions. Standalone locker locations belong to a
client but need neither a condominium nor a unit.

Resident lists filter by owner or audience. Staff-only request comments are
filtered separately from resident-visible history. Public lost-pet notices omit
private owner/unit/vaccination data. Inbox entries cease to be visible when their
location membership or feature access is revoked. Uploaded private attachments
require ownership or an explicit read-all grant; published attachments require
location document access.

## Domain consistency

- Package reports do not confirm physical pickup. Authorized staff or validated
  integration events must confirm collection and consume the pickup credential.
- Package receipt, reservations, account invitations, and visitor invitations
  require idempotency keys. Read the module contracts for return/replay semantics;
  one-time invitation credentials are never reissued on a duplicate request.
- Locker provider events have dedicated credentials, binding to configured
  integrations, event deduplication, timestamp checks, and state validation.
- Reservations use facility time zones, operating rules, and half-open intervals.
  Pending approvals reserve capacity, preventing approval-time overbooking.
- Event attendance, document acknowledgments, notice receipts, and inbox read
  status avoid duplicate records for repeated actions.
- Visitor admissions require the staff action, a valid credential, an active
  inviter, the allowed time window, and the configured single-use rule.
- Work orders, request status changes, vehicle movements, and administrative
  changes retain audit/history records under the same tenant/location boundary.
- Request escalation reasons and concierge handovers remain staff-only. Event
  reservation links validate ownership, location, time coverage, and exclusive use.

## Provider and storage boundaries

Authentication email has a real SMTP adapter. In-app notifications are persisted
independently of external push/SMS/email delivery. Camera live view/recording,
physical locker opening, and other hardware commands require real provider
adapters and credentials; unsupported operations return an explicit 501.
The integration event contract accepts trusted provider events but does not
simulate hardware or claim that a physical action occurred.

Managed attachments are stored in the database with a 2 MiB decoded limit,
PNG/JPEG/PDF signature checks, ownership, and download authorization. This is
suitable for small files; larger deployments should add object storage, scanning,
retention, and tested authorization-preserving download adapters. Domain metadata
may also reference HTTPS documents hosted elsewhere; those providers enforce
their URL access policies independently.

## Runtime and operations

`/health/live` confirms HTTP handling. `/health/ready` checks the database and
returns 503 when unavailable. It does not certify SMTP or hardware availability.
The default listener is `127.0.0.1`; containers use an explicit `HOST=0.0.0.0`.
Production configuration requires PostgreSQL credentials and authenticated SMTP
with STARTTLS. TLS termination, database encryption/backups, restore drills,
monitoring, and retention must be configured for the deployment.

HTTP logs contain method, status, and a generated request ID. Errors return a
stable code and safe message with that ID. Unexpected-error logs omit exception
messages that might contain secrets. Request bodies, credentials, and private
records are not written to HTTP logs.

Bootstrap runs as an operator CLI command and creates a client administrator in
one transaction. The client-state CLI can disable a client and revoke associated
credentials. Application HTTP endpoints do not expose unrestricted cross-client
administration. Shutdown cancels managed worker coroutines and closes the pool.

The [README](../README.md) maps working modules to the broader product scope.
The [OpenAPI contract](openapi.yaml) lists routes and request models; the module
guides explain domain rules and external-provider boundaries.
