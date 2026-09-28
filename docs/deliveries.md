# Encomendas e lockers v1

As rotas e schemas estão no [OpenAPI](openapi.yaml). O morador usa `/v1/memberships/{membershipId}/parcels`; a equipe usa `/v1/ops/parcels` para registrar e entregar encomendas e `/v1/admin/condominiums/{condominiumId}/parcels` para administrá-las. O servidor verifica vínculo, permissão, condomínio e estado da encomenda em cada operação.

## Recebimento e retirada

`POST /v1/ops/parcels` registra entrega na portaria ou em um compartimento configurado. A criação é idempotente quando o contrato exige `Idempotency-Key`. O destinatário consulta seu código em `GET /v1/memberships/{membershipId}/parcels/{parcelId}/pickup-credential`; o código é sigiloso, tem prazo e pode ser revogado ou reemitido após mudança de delegação. Delegados precisam de vínculo explícito.

`POST /v1/memberships/{membershipId}/parcels/{parcelId}/manual-pickup` registra relato manual e revoga a credencial, mas não confirma a retirada física. A equipe confirma entrega em `POST /v1/ops/parcels/{parcelId}/handover`. Um evento `pickup` autenticado do locker também pode confirmar a coleta. A transição física libera o compartimento e consome a credencial dentro da transação. Repetir um evento com o mesmo identificador e payload devolve resultado duplicado; reutilizar o identificador com outro payload é rejeitado.

## Equipamentos e eventos

Administradores criam o locker em `/v1/admin/condominiums/{condominiumId}/lockers`, associam um dispositivo ativo e definem compartimentos em `PUT .../lockers/{lockerId}/compartments`. O dispositivo usa `X-Device-Key` em `POST /v1/ops/lockers/{lockerId}/events` e `/credential-validations`. O contrato `LockerEvent` define os tipos, campos e códigos aceitos. Eventos `heartbeat` atualizam a disponibilidade observada; `door_opened` registra abertura física. A API não infere que uma encomenda foi retirada apenas porque uma porta abriu.

## Abertura remota

`POST /v1/admin/condominiums/{condominiumId}/lockers/{lockerId}/compartments/{compartmentCode}/open` exige `lockers.manage`, verificação recente de identidade, motivo e `Idempotency-Key`. Configure `LOCKER_PROVIDER_BASE_URL` e `LOCKER_PROVIDER_TOKEN` no `.env` do Compose. Em produção, a URL deve usar HTTPS. O provedor recebe:

```text
POST {LOCKER_PROVIDER_BASE_URL}/lockers/{lockerId}/compartments/{compartmentCode}/open
Authorization: Bearer {LOCKER_PROVIDER_TOKEN}
Idempotency-Key: {commandId}
Content-Type: application/json

{"commandId":"uuid-estavel","deviceId":"uuid-do-dispositivo","reason":"motivo"}
```

O provedor deve responder HTTP 202. Se enviar corpo JSON, ele deve conter o mesmo `commandId`. A API então devolve `status: command_sent`, registra o comando na auditoria e aguarda um evento confiável do equipamento para observar a abertura real. A mesma chave de idempotência produz o mesmo `commandId` em uma tentativa repetida, inclusive após perda de resposta do provedor. Sem provedor configurado a operação devolve 501 `PROVIDER_NOT_CONFIGURED`; falha ou recusa do provedor devolve 503. Não use esse comando como comprovante de retirada.

O Compose conserva uploads e PostgreSQL em volumes. Operações em vários hosts precisam de armazenamento compartilhado para arquivos e de testes de carga do banco e dos provedores.
and provider-specific webhook signature formats still require the selected
vendor's contract and credentials. No endpoint simulates a physical door opening.
