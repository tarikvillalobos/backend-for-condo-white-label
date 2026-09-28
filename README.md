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

O comando imprime o ID da marca. Envie esse ID no cabeçalho `X-Brand-Id` em todas as chamadas de negócio. A marca identifica o tenant e sua configuração; ela não concede permissão. O login é `POST /v1/auth/password/login` com `identifier` e `password`; use o `accessToken` como `Authorization: Bearer ...`. A senha inicial de teste deve atender à política retornada pela API.

## Ver o OpenAPI

- Interface Swagger: [http://127.0.0.1:8080/docs](http://127.0.0.1:8080/docs)
- YAML servido pela API: [http://127.0.0.1:8080/v1/openapi.yaml](http://127.0.0.1:8080/v1/openapi.yaml)
- JSON servido pela API: [http://127.0.0.1:8080/v1/openapi.json](http://127.0.0.1:8080/v1/openapi.json)
- Arquivo versionado: [docs/openapi.yaml](docs/openapi.yaml)

A interface Swagger carrega seus recursos de uma CDN; os arquivos YAML e JSON são servidos pelo próprio contêiner. `GET /v1/health/live` responde quando o servidor está vivo; `GET /v1/health/ready` verifica o banco e retorna 503 quando não está pronto.

## Funcionalidade

Os 294 métodos do contrato têm handlers registrados. Há fluxos para identidade e sessões, estrutura condominial, moradores e equipe, organizações, portaria e visitantes, encomendas, lockers e equipamentos, reservas, comunicação, pets, veículos, documentos, manutenção, relatórios, exportações e auditoria. O armazenamento é segregado por tenant e marca; permissões dependem do vínculo, do papel e do contexto.

A API valida entradas e respostas com os schemas do OpenAPI, exige chaves de idempotência onde o contrato determina e usa ETag/`If-Match` nas alterações versionadas. A auditoria registra requisições, eventos e alterações de linhas; exportações e avisos agendados rodam em workers. Arquivos privados usam URLs assinadas por tempo limitado e validação de tamanho, tipo e assinatura do conteúdo.

Recursos externos dependem de configuração real. O Compose captura e-mail no Mailpit; SMS e WhatsApp indicam indisponibilidade. Vídeo de câmeras exige um provedor configurado. Operações físicas de lockers e portões exigem equipamento e credenciais próprios: a API não transforma um comando em prova de retirada ou entrada. O canal push ainda requer integração de envio com o provedor.

## Implantação e crescimento

A produção exige PostgreSQL, `APP_ENV=production`, `API_ENCRYPTION_KEY` estável, SMTP autenticado com STARTTLS e terminação TLS na frente da API. Use o [guia operacional](docs/operations.md) para configuração, backups e verificação.

As rotas `/v1` usam transações e locks por escopo, índices para consultas principais, cursores com snapshot e jobs com controle de concorrência. Aumente réplicas da API e ajuste o pool de conexões conforme testes de carga reais. O volume de uploads do Compose serve a um único host Docker; uma implantação em vários hosts precisa de armazenamento de objetos compartilhado. A capacidade para um milhão de usuários depende de carga simultânea, banco, rede, tamanho dos dados e integrações, e deve ser medida antes de assumir esse número.

## Verificação e commits

```sh
./gradlew test installDist
python3 scripts/smoke_test.py
python3 scripts/check_commits.py
build/spec-venv/bin/python scripts/check_openapi.py
```

Crie `build/spec-venv` e instale `requirements-dev.txt` antes do último comando se o ambiente ainda não tiver as dependências Python. Os commits na `main` usam `tarik.villalobos@gmail.com`, um arquivo por commit e até 20 linhas alteradas; `docs/openapi.yaml` é a exceção autorizada.
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
