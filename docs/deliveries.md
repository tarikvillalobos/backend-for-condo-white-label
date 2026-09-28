# Encomendas e lockers v1

As rotas e schemas estão no [OpenAPI](openapi.yaml). O morador usa `/v1/memberships/{membershipId}/parcels`; a equipe usa `/v1/ops/parcels` para registrar e entregar encomendas e `/v1/admin/condominiums/{condominiumId}/parcels` para administrá-las. O servidor verifica vínculo, permissão, condomínio e estado da encomenda em cada operação.

## Recebimento e retirada

`POST /v1/ops/parcels` registra entrega na portaria ou em um compartimento configurado. A criação é idempotente quando o contrato exige `Idempotency-Key`. O destinatário consulta seu código em `GET /v1/memberships/{membershipId}/parcels/{parcelId}/pickup-credential`; o código é sigiloso, tem prazo e pode ser revogado ou reemitido após mudança de delegação. Delegados precisam de vínculo explícito.

| Method and path | Permission and behavior |
| --- | --- |
| `GET /packages` | `packages.read.own` or `packages.read.all`; filters ownership |
| `GET /packages/{id}` | Same; includes safe status and event history |
| `POST /packages` | `packages.receive`; requires `Idempotency-Key` |
| `POST /packages/{id}/credential` | Recipient with `packages.read.own`; returns secret once |
| `DELETE /packages/{id}/credential` | Recipient; invalidates the credential immediately |
| `POST /packages/{id}/delegates` | Recipient; explicitly authorizes an active member |
| `DELETE /packages/{id}/delegates/{userId}` | Recipient; revokes delegation |
| `POST /packages/{id}/report-pickup` | Recipient or delegate; records an unconfirmed report |
| `POST /packages/{id}/confirm-pickup` | `packages.collect`; validates collector and credential |
| `POST /packages/{id}/remind` | `packages.receive`; inbox reminder, at most once per 24 hours |
| `POST /packages/{id}/cancel` | `packages.manage`; cancels an outstanding delivery |
| `GET /lockers` | `lockers.manage`; includes compartment occupancy |
| `POST /lockers` | `lockers.manage`; creates locker and compartments |
| `PUT /lockers/{id}` | `lockers.manage`; updates names and maintenance settings |
| `POST /lockers/{id}/open` | `lockers.manage`; currently returns `501 provider_unavailable` |
| `POST /locker-events` | Dedicated integration credential; accepts trusted pickup confirmation |

## Receive and collect

Example receipt body:

```json
{
  "recipientId": "recipient-account-id",
  "description": "Small parcel",
  "carrier": "Carrier name",
  "trackingNumber": "TRACK123",
  "lockerId": "locker-id",
  "compartmentId": "A1",
  "collectionDeadline": "2030-01-04T20:00:00Z"
}
```

Omit both locker fields for reception desk storage. The deadline is an
informational collection target. A reminder is an explicit staff action.
Reusing an idempotency key with the same body returns the original receipt;
reusing it with a different body returns `409`. Receipt creation, compartment
allocation, audit, and recipient notification commit in one transaction.

Issue a credential with `{"validForMinutes":30}`; validity can be 1 to 1440
minutes. The response contains `credential` and `expiresAt` with `Cache-Control:
no-store`. Only a SHA-256 digest is persisted. A new credential revokes its
predecessor. Delegation changes revoke the current credential, so the recipient
must explicitly issue and share a replacement with the intended collector.

Delegate with `{"userId":"delegate-account-id"}`. Household or unit membership
alone never grants delivery visibility or collection permission. Delegates can
view only the deliveries explicitly delegated to them and report a pickup.
Only the recipient can manage delegates and issue credentials.

Staff confirmation requires `{"collectorId":"account-id","credential":"secret"}`.
It verifies active membership, recipient or delegate status, expiration, and the
credential digest. Collection consumes the credential and releases the
compartment atomically. A resident report sets `PICKUP_REPORTED`; it does not
confirm collection or release storage. All duplicate collection attempts fail
after the first confirmed pickup. Cancellation also releases storage and revokes
credentials. Secrets never appear in package views, audit, or notifications.

## Lockers and provider events

Create a locker using `name`, optional `maintenance`, optional `integrationId`,
and `compartments`: `[{"id":"A1","label":"A1","maintenance":false}]`.
Compartment IDs are unique within a locker. Occupancy is controlled by delivery
operations. Occupied compartments cannot be removed or reassigned via locker
updates. Maintenance blocks new deliveries without discarding existing ones.

For a provider event, configure an active integration of type `locker` and bind
its ID to the locker. Use its dedicated credential on `/locker-events`; human
session tokens are not accepted there. The event body is:

```json
{
  "eventId": "provider-event-123",
  "packageId": "package-id",
  "compartmentId": "A1",
  "collectorId": "recipient-or-delegate-id",
  "occurredAt": "2030-01-02T12:00:00Z",
  "type": "pickup_confirmed"
}
```

The provider must establish the physical collection and collector identity before
sending an event. The server checks integration binding, location, compartment,
recipient authorization, and event time. A repeated event with the same payload
returns its original result. Reuse of an event ID with changed content returns
`409`. Events predating receipt or arriving after cancellation/collection are
recorded as `ignored`; events more than five minutes in the future are rejected.

This is an authenticated ingestion contract, not a hardware adapter. Door opening
and provider-specific webhook signature formats still require the selected
vendor's contract and credentials. No endpoint simulates a physical door opening.
