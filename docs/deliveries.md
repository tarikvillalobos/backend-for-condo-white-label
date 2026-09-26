# Packages and smart lockers

All human routes require `Authorization: Bearer <session-token>` and an active
membership in the selected location. The client and location must enable the
`packages` feature. Standalone locations use the same records and permissions;
neither units nor condominium relationships are required.

The prefix below is `/api/v1/locations/{locationId}`. Lists return a page with
`items`, `total`, `offset`, and `limit`. Identifiers from another location or
client cannot be used to access or modify a resource.

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
