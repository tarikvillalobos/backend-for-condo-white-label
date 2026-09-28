# Guia da API v1

O contrato completo é [openapi.yaml](openapi.yaml). Com Docker ativo, consulte a interface em `http://127.0.0.1:8080/docs`, o YAML em `/v1/openapi.yaml` e o JSON em `/v1/openapi.json`. Todas as rotas de negócio começam em `/v1`.

## Primeira chamada

Siga o [README](../README.md) para iniciar o Compose e criar a primeira marca com `bootstrap`. O exemplo abaixo usa `curl` e `jq`. Guarde o ID impresso pelo comando e envie-o em `X-Brand-Id`. O cabeçalho seleciona a marca; cada operação ainda exige a autenticação e as permissões indicadas no OpenAPI.

```sh
export API_BASE=http://127.0.0.1:8080
export BRAND_ID='id-impresso-pelo-bootstrap'
export ADMIN_EMAIL='admin@example.test'
read -r -s -p 'Senha: ' ADMIN_PASSWORD; printf '\n'
curl -sS "$API_BASE/v1/configuration" -H "X-Brand-Id: $BRAND_ID"
curl -sS "$API_BASE/v1/auth/password/login" \
  -H "X-Brand-Id: $BRAND_ID" -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg identifier "$ADMIN_EMAIL" --arg password "$ADMIN_PASSWORD" \
      '{identifier:$identifier,password:$password}')"
```

O login devolve `accessToken`. Nas operações protegidas, envie `Authorization: Bearer <accessToken>`. Se uma rota exigir `StaffBearer`, use uma sessão de equipe; equipamentos usam `X-Device-Key` nas rotas de hardware autorizadas. Os contextos de morador são listados por `GET /v1/me/contexts`.

## Regras comuns

- Envie `Content-Type: application/json` quando houver corpo JSON. O servidor valida entrada e resposta contra os schemas do contrato.
- Operações que declaram `Idempotency-Key` exigem um UUID. Repetir a mesma chave e o mesmo corpo devolve a resposta original; reutilizá-la com outro corpo devolve 409.
- Alterações que declaram `If-Match` exigem o ETag atual. Uma versão antiga devolve 412. Consulte o recurso novamente antes de tentar outra alteração.
- Listas paginadas devolvem `items` e `page.nextCursor`. Envie `cursor` com os mesmos filtros para avançar no snapshot; o cursor expira em 15 minutos.
- Erros v1 usam `application/problem+json`, com `code`, `detail`, `status` e `requestId`. O servidor também envia `X-Request-ID`.
- Módulos desativados na marca, no condomínio ou no vínculo respondem 403 `MODULE_DISABLED` nas operações correspondentes.

## Áreas do contrato

O OpenAPI agrupa autenticação e perfil; estrutura e pessoas; encomendas, lockers e portaria; reservas; comunicação, documentos, pets e veículos; manutenção; relatórios; organizações; integrações; e auditoria. Consulte cada operação para o corpo, as permissões, os possíveis códigos e o escopo do identificador. Os guias de [identidade](identity.md), [encomendas](deliveries.md), [reservas](reservations.md) e [comunidade](community.md) explicam os fluxos principais.

`GET /v1/health/live` confirma a resposta HTTP. `GET /v1/health/ready` verifica a conexão com o banco. Para configuração, backup e limites de implantação, consulte [operations.md](operations.md).
