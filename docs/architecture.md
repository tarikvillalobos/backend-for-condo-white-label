# Arquitetura da Community API

A API é um monólito Kotlin/Ktor para SmartLocker e Condo. A aplicação pública registra as rotas do [OpenAPI v1](openapi.yaml) em `src/main/kotlin/com/community/api/v1/`. Os pacotes `identity`, `platform`, `deliveries`, `reservations` e `community` concentram os fluxos; `core` contém banco e infraestrutura compartilhada. O ponto de entrada é `Application.kt`.

## Persistência e isolamento

PostgreSQL 17 é o banco de produção. Flyway aplica as migrações em `src/main/resources/db/migration/` e `db/postgresql/`; H2 em modo compatível é usado nos testes e no smoke test da distribuição. `app_records` armazena os registros de domínio com `tenant_id`, `location_id`, proprietário, versão e payload JSON. `V1Store` verifica tenant e marca em leituras e alterações. A marca é resolvida a partir de `X-Brand-Id`, mas o cabeçalho não concede permissão.

As rotas v1 usam transações com locks por escopo, controle de versão, ETag e chaves de idempotência. Listas principais usam versões históricas, índices e cursores assinados com snapshot de 15 minutos. Coleções derivadas pequenas são materializadas com limite de 5.000 itens; filtros mais estreitos são necessários acima desse limite. Ajuste `DB_POOL_SIZE` junto com o número de réplicas e a capacidade do PostgreSQL.

## Autorização e auditoria

Sessões de morador, sessões de equipe e chaves de dispositivo têm credenciais distintas. O servidor combina permissões, contexto ativo, escopo do condomínio e módulos habilitados. Operações sensíveis exigem verificação recente da identidade quando o OpenAPI declara `x-step-up`.

As rotas de negócio registram requisições; operações auditadas registram eventos e alterações. O log de eventos tem cadeia de hashes por marca ou condomínio. A verificação de integridade está exposta às permissões administrativas indicadas no contrato. A cadeia ajuda a detectar mudanças indevidas, mas a segurança operacional também depende de restringir acesso ao banco e proteger backups externos.

## Processos externos

Workers processam e-mail, notificações, exportações, webhooks, limpeza de uploads e pedidos de exclusão de dados. O Compose usa Mailpit para capturar e-mails de teste. SMS, WhatsApp e push exigem provedores próprios; a API informa indisponibilidade onde não há integração ativa.

Câmeras usam `CAMERA_PROVIDER_BASE_URL` e `CAMERA_PROVIDER_TOKEN`. Abertura remota de locker usa `LOCKER_PROVIDER_BASE_URL` e `LOCKER_PROVIDER_TOKEN` segundo o [protocolo de lockers](deliveries.md). Um comando aceito pelo provedor não comprova abertura física: somente o evento autenticado do equipamento confirma o estado. Webhooks exigem destino HTTPS público e devem operar com proteção de saída de rede.

Uploads privados usam URLs assinadas e o diretório `UPLOAD_DIRECTORY`. O volume do Compose atende um único host Docker; múltiplos hosts precisam de armazenamento de objetos compartilhado. Consulte [operations.md](operations.md) para instalação, backup, monitoramento e limites de escala. A capacidade de atender um milhão de usuários precisa ser medida com tráfego e dados representativos.
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
