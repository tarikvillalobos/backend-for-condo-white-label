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
