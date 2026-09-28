# Operação da Community API

## Subir localmente

Instale e inicie o Docker Desktop. Na raiz do projeto, crie `.env` com `DATABASE_PASSWORD` e `API_ENCRYPTION_KEY`. A senha pode ser gerada com `openssl rand -hex 24`; a chave precisa codificar 32 bytes em Base64 URL, por exemplo `openssl rand -base64 32 | tr '+/' '-_' | tr -d '='`. Guarde os mesmos valores para os reinícios: trocar a senha não redefine o usuário do PostgreSQL já criado, e trocar a chave torna dados cifrados ilegíveis.

```sh
docker compose up -d --build
docker compose ps
curl http://127.0.0.1:8080/v1/health/ready
```

A primeira marca é criada com `docker compose run --rm -e BOOTSTRAP_CLIENT_NAME -e BOOTSTRAP_EMAIL -e BOOTSTRAP_PASSWORD api bootstrap`, depois de exportar essas três variáveis no shell. O comando imprime o ID a usar como `X-Brand-Id`. O Mailpit de desenvolvimento fica em `http://127.0.0.1:8025`. `docker compose down` conserva os volumes; mantenha cópias de segurança do banco e dos uploads.

O contrato pode ser visto em `/docs`, `/v1/openapi.yaml` e `/v1/openapi.json`. O login por senha é `POST /v1/auth/password/login`. Cada operação indica no OpenAPI os cabeçalhos de autenticação, idempotência e `If-Match` necessários.

## Produção

Use Java 21, PostgreSQL, `APP_ENV=production`, `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` e `API_ENCRYPTION_KEY`. Configure SMTP autenticado com STARTTLS para desafios e mensagens. Termine HTTPS em um proxy confiável. Mantenha credenciais em um gerenciador de segredos; não grave o arquivo `.env` no Git. A distribuição é criada por `./gradlew installDist`; o Dockerfile executa a mesma distribuição como UID 10001.

O endpoint `/v1/health/live` verifica a resposta HTTP. `/v1/health/ready` consulta o banco e retorna 503 quando indisponível. Monitore falhas 5xx, disponibilidade do banco, filas de e-mail, exportações, webhooks e espaço dos volumes. Os logs HTTP contêm método, status e ID de requisição, sem corpos ou tokens.

Flyway aplica migrações ao iniciar. O PostgreSQL deve ter backup agendado e recuperação para um ponto no tempo. Faça backup também do diretório `UPLOAD_DIRECTORY` ou do volume `uploads`; arquivos e metadados precisam ser restaurados juntos. Teste a restauração em ambiente isolado. Não execute testes com a base de produção.

## Concorrência, retenção e provedores

As rotas `/v1` usam locks por escopo, controle de versão e idempotência. Consultas principais são indexadas e listas usam cursores com snapshot de 15 minutos. Os workers processam e-mail, comunicados agendados, exportações, webhooks e pedidos de exclusão. O PostgreSQL e os workers devem ser monitorados quando aumentar o número de réplicas e conexões.

O volume de arquivos do Compose é compartilhado apenas no mesmo host Docker. Para vários hosts, adapte `UPLOAD_DIRECTORY` a um armazenamento de objetos compartilhado antes de distribuir as réplicas. Meça latência e vazão com a carga esperada; o número de usuários cadastrados, sozinho, não define capacidade.

Retenções legais podem bloquear exclusão de dados. O worker de privacidade verifica os pedidos antes de anonimizar; eventos de auditoria preservam a cadeia de hashes e ocultam segredos. A cadeia detecta alterações acidentais ou não autorizadas nos registros sob a política operacional, mas um operador com controle total do banco e da aplicação pode recomputar hashes. Restrinja esse acesso e proteja backups externos.

Câmeras precisam das variáveis do provedor de vídeo. Webhooks aceitam destinos HTTPS públicos na porta 443 e devem sair por uma rede com proteção contra acesso a endereços internos. SMS, WhatsApp e entrega push precisam de provedores próprios; o Compose não os envia. O Mailpit captura e-mails sem entregá-los externamente. A API não presume retirada de encomenda ou entrada física sem evento confiável do equipamento.

## Verificação

```sh
./gradlew test installDist
python3 scripts/smoke_test.py
python3 scripts/check_commits.py
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
