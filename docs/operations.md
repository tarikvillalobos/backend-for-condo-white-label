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

Câmeras usam `CAMERA_PROVIDER_BASE_URL` e `CAMERA_PROVIDER_TOKEN`. Abertura remota usa `LOCKER_PROVIDER_BASE_URL` e `LOCKER_PROVIDER_TOKEN`; o [protocolo do comando](deliveries.md#abertura-remota) exige resposta 202 e `commandId` idempotente. No Compose, configure essas variáveis no `.env`. Em produção, os dois provedores devem usar HTTPS. Webhooks aceitam destinos HTTPS públicos na porta 443 e devem sair por uma rede com proteção contra acesso a endereços internos. SMS, WhatsApp e entrega push precisam de provedores próprios; o Compose não os envia. O Mailpit captura e-mails sem entregá-los externamente. A API não presume retirada de encomenda ou entrada física sem evento confiável do equipamento.

O destinatário do webhook recebe `X-Community-Event-Id` e `X-Signature: t=<segundos Unix>,v1=<hex>`. Calcule `HMAC-SHA256(secret, t + "." + corpo bruto UTF-8)` em hexadecimal e compare com `v1` usando comparação de tempo constante. O corpo segue o schema `WebhookEnvelope`, com `condominiumId` e `data`. Responda 2xx em até 10 segundos e deduplique pelo ID do evento. Falhas recebem tentativas com backoff exponencial durante até 24 horas; após esse prazo o evento é ignorado pela assinatura. Guarde o segredo devolvido somente na criação do webhook.

## Verificação

```sh
./gradlew test installDist
python3 scripts/smoke_test.py
python3 scripts/check_commits.py
python3 -m venv build/spec-venv
build/spec-venv/bin/pip install -r requirements-dev.txt
build/spec-venv/bin/python scripts/check_openapi.py
```

O smoke test usa um banco temporário e testa bootstrap, login, idempotência, auditoria e persistência após reinício. O workflow de CI também executa testes com PostgreSQL e compila a imagem Docker.
