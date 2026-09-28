# Guia da API v1

O contrato completo é [openapi.yaml](openapi.yaml). Com Docker ativo, consulte a interface em `http://127.0.0.1:8080/docs`, o YAML em `/v1/openapi.yaml` e o JSON em `/v1/openapi.json`. Todas as rotas de negócio começam em `/v1`.

## Primeira chamada

Siga o [README](../README.md) para iniciar o Compose e criar a primeira marca com `bootstrap`. Guarde o ID impresso pelo comando e envie-o em `X-Brand-Id`. O cabeçalho seleciona a marca; cada operação ainda exige a autenticação e as permissões indicadas no OpenAPI.

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
permissions. Omitted locker fields mean reception desk storage.

```bash
PARCEL=$(jq -nc --arg recipientId "$RESIDENT_ID" \
  '{recipientId:$recipientId,description:"Walkthrough parcel",carrier:"Example carrier"}' |
  api_json POST "$ADMIN_TOKEN" "/api/v1/locations/$LOCATION_ID/packages" \
    -H "Idempotency-Key: parcel-$LOCATION_ID")
PACKAGE_ID=$(printf '%s' "$PARCEL" | jq -er .id)

PICKUP=$(printf '%s' '{"validForMinutes":30}' |
  api_json POST "$RESIDENT_TOKEN" \
    "/api/v1/locations/$LOCATION_ID/packages/$PACKAGE_ID/credential")
PICKUP_CREDENTIAL=$(printf '%s' "$PICKUP" | jq -er .credential)

jq -nc --arg collectorId "$RESIDENT_ID" --arg credential "$PICKUP_CREDENTIAL" \
  '{collectorId:$collectorId,credential:$credential}' |
  api_json POST "$ADMIN_TOKEN" \
    "/api/v1/locations/$LOCATION_ID/packages/$PACKAGE_ID/confirm-pickup"
```

The result is `COLLECTED`. The credential is consumed and any locker compartment
is released in the same transaction. A second confirmation fails with `409`.
The resident's separate `/report-pickup` action records a report and keeps the
delivery outstanding until staff or an authenticated provider confirms it.

## 4. Create a facility and reserve tomorrow's local afternoon

```bash
FACILITY=$(printf '%s' '{"name":"Meeting room","timeZone":"America/Sao_Paulo","capacity":10,"opensAt":"08:00","closesAt":"22:00"}' |
  api_json POST "$ADMIN_TOKEN" "/api/v1/locations/$LOCATION_ID/facilities")
FACILITY_ID=$(printf '%s' "$FACILITY" | jq -er .id)

START_AT=$(python3 - <<'PY'
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo
tomorrow = datetime.now(ZoneInfo("America/Sao_Paulo")) + timedelta(days=1)
start = tomorrow.replace(hour=14, minute=0, second=0, microsecond=0)
print(start.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"))
PY
)
END_AT=$(python3 - "$START_AT" <<'PY'
import sys
from datetime import datetime, timedelta
start = datetime.fromisoformat(sys.argv[1].replace("Z", "+00:00"))
print((start + timedelta(hours=1)).isoformat().replace("+00:00", "Z"))
PY
)

BOOKING=$(jq -nc \
  --arg facilityId "$FACILITY_ID" --arg startsAt "$START_AT" --arg endsAt "$END_AT" \
  '{facilityId:$facilityId,startsAt:$startsAt,endsAt:$endsAt,attendees:3}' |
  api_json POST "$RESIDENT_TOKEN" "/api/v1/locations/$LOCATION_ID/reservations" \
    -H "Idempotency-Key: booking-$FACILITY_ID")
printf '%s' "$BOOKING" | jq '{id,status:.details.status}'
```

The booking is `CONFIRMED` by default, or `PENDING` when the facility requires
approval. Both states block overlapping reservations, including concurrent
requests. A matching idempotent retry returns the same booking.

## 5. Publish an event linked to that reservation

The administrator can link another member's reservation because the role has
`reservations.manage`. A publisher without that permission can link only their
own booking. The event must fit the booking interval, and a booking can back
only one active event.

```bash
BOOKING_ID=$(printf '%s' "$BOOKING" | jq -er .id)
jq -nc --arg reservationId "$BOOKING_ID" \
  --arg startsAt "$START_AT" --arg endsAt "$END_AT" \
  '{title:"Community meeting",description:"Walkthrough event",capacity:3,reservationId:$reservationId,startsAt:$startsAt,endsAt:$endsAt}' |
  api_json POST "$ADMIN_TOKEN" "/api/v1/locations/$LOCATION_ID/events"
```

Links do not approve a pending booking or synchronize cancellations. Cancelling
an event and cancelling its reservation are separate operations.

## Response conventions

- Most administration and community resources use a `Record` envelope with
  `id`, `kind`, `tenantId`, `locationId`, `ownerId`, `data`, timestamps, and version.
- Packages, lockers, reservations, visitors, and attachments use dedicated safe
  view objects. Their fields are defined in the OpenAPI response schemas.
- Paginated lists use `items`, `total`, `offset`, and `limit`. The default limit
  is 50 and the maximum is 200. Session and role lists are plain arrays.
- Errors include `code`, `message`, and `requestId`. Preserve the response's
  `X-Request-ID` when reporting an error.
- Readiness is available without authentication at `GET /health/ready`.
- Locker opening and camera viewing/recordings return `501` until a supported
  hardware provider is integrated. They do not simulate physical actions.

Only replay requests when their endpoint documents retry behavior. Package and
reservation keys return the original result; account and visitor invitation
keys return a conflict after the first successful issuance.
