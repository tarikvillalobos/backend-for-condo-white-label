# Identidade e sessões v1

O fluxo completo está no [OpenAPI](openapi.yaml), nas seções Authentication e Profile. Todas as chamadas de negócio enviam `X-Brand-Id`; a autenticação também depende da marca. A primeira conta administrativa é criada pelo comando `bootstrap` descrito no [README](../README.md).

## Entrar e renovar

- `POST /v1/auth/password/login` recebe `identifier` e `password` e devolve tokens de acesso e renovação.
- `POST /v1/auth/challenges` inicia o acesso por código. `POST /v1/auth/challenges/{challengeId}/verify` conclui o desafio. O Compose entrega e-mail no Mailpit; outros canais dependem de provedor configurado.
- `POST /v1/auth/refresh` renova a sessão; `POST /v1/auth/logout` a encerra. Rotação e revogação invalidam credenciais antigas.
- Equipe que precisa de segundo fator usa `POST /v1/auth/mfa/{challengeId}/verify`, conforme a resposta de autenticação e as exigências do contrato.

Use `Authorization: Bearer <accessToken>` nas rotas protegidas. `GET /v1/me/contexts`, `/v1/me/memberships` e `/v1/me/staff-assignments` mostram os contextos disponíveis. Permissão de equipe e acesso de morador são avaliados pelo vínculo e pelo escopo de cada requisição.

## Convites, conta e segurança

Convites são consultados em `GET /v1/auth/invitations/{code}` e aceitos em `POST /v1/auth/invitations/{code}/accept`. Uma conta já autenticada vincula outro convite em `POST /v1/me/invitations/{code}/link`. Alterações de senha, recuperação, troca de contato e preferências têm rotas próprias no contrato.

`POST /v1/me/verify` confirma identidade recente para operações com `x-step-up`; o contrato informa quando usar o desafio adicional `/v1/me/verify/challenge`. `GET /v1/me/sessions` lista sessões; as rotas `DELETE /v1/me/sessions/{sessionId}` e `POST /v1/me/sessions/revoke-others` as revogam.

`GET` e `PATCH /v1/me/privacy` tratam preferências de privacidade. Pedidos de dados usam `POST` e `GET /v1/me/data-requests`, com processamento assíncrono sujeito a retenções legais. O cadastro de instalação push guarda a inscrição em `/v1/devices/{installationId}/push-registration`; o envio push permanece indisponível até configurar um emissor real.

Respostas de erro usam `application/problem+json` e `requestId`. Consulte os schemas e códigos de cada operação antes de construir o cliente.
