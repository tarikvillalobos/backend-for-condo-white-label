# Guia da API v1

O contrato completo é [openapi.yaml](openapi.yaml). Com Docker ativo, consulte a interface em `http://127.0.0.1:8080/docs`, o YAML em `/v1/openapi.yaml` e o JSON em `/v1/openapi.json`. Todas as rotas de negócio começam em `/v1`.

The complete request and response contract is [openapi.yaml](openapi.yaml).
It describes 161 implemented operations, including the public authentication
routes and the separate credential required for locker provider events.

This walkthrough uses a local development client created with the `bootstrap`
command in the [README](../README.md#getting-started). Start the API first with
`./gradlew run`. The examples require Bash, curl, jq, and Python 3.9 or later.
Use a test client: these calls create real application records in its database.

## 1. Sign in as the bootstrapped administrator

Use the client ID printed by bootstrap and the administrator credentials you
configured. The password is read from the terminal without echoing it.

```bash
export API_BASE=http://localhost:8080
export TENANT_ID=replace-with-bootstrap-client-id
export ADMIN_EMAIL=admin@example.test
read -r -s -p 'Administrator password: ' ADMIN_PASSWORD; printf '\n'

ADMIN_SESSION=$(jq -nc \
  --arg tenantId "$TENANT_ID" --arg email "$ADMIN_EMAIL" \
  --arg password "$ADMIN_PASSWORD" \
  '{tenantId:$tenantId,email:$email,password:$password,device:"API walkthrough"}' |
  curl -fsS "$API_BASE/api/v1/auth/login" \
    -H 'Content-Type: application/json' --data-binary @-)
ADMIN_TOKEN=$(printf '%s' "$ADMIN_SESSION" | jq -er .accessToken)
```

For the following calls, define a helper that sends JSON from standard input:

```bash
api_json() {
  local method="$1" token="$2" path="$3"
  shift 3
  curl -fsS -X "$method" "$API_BASE$path" \
    -H "Authorization: Bearer $token" \
    -H 'Content-Type: application/json' "$@" --data-binary @-
}
```

Access tokens expire after 15 minutes. `/api/v1/auth/refresh` accepts
`{"refreshToken":"..."}` and returns a new access/refresh pair. Keep the newest
pair: reusing a consumed refresh token revokes that session.

## 2. Create a location and invite a resident

The `condominium` kind supports residential units. For a standalone locker
deployment, change `kind` to `standalone`; package workflows do not require units.

```bash
LOCATION=$(printf '%s' '{"name":"API walkthrough","kind":"condominium","timeZone":"America/Sao_Paulo"}' |
  api_json POST "$ADMIN_TOKEN" /api/v1/locations)
LOCATION_ID=$(printf '%s' "$LOCATION" | jq -er .id)

RESIDENT_EMAIL=resident-walkthrough@example.test
INVITATION=$(jq -nc \
  --arg email "$RESIDENT_EMAIL" --arg locationId "$LOCATION_ID" \
  '{email:$email,name:"Walkthrough resident",locationId:$locationId,role:"resident"}' |
  api_json POST "$ADMIN_TOKEN" "/api/v1/locations/$LOCATION_ID/invitations" \
    -H "Idempotency-Key: resident-$LOCATION_ID")
RESIDENT_ID=$(printf '%s' "$INVITATION" | jq -er .userId)
ACTIVATION_TOKEN=$(printf '%s' "$INVITATION" | jq -er .token)
```

Invitations require recent password verification. If the API returns
`403 verification_required`, verify and repeat the invitation with the same key:

```bash
jq -nc --arg password "$ADMIN_PASSWORD" '{password:$password}' |
  api_json POST "$ADMIN_TOKEN" /api/v1/me/verify
```

In an actual onboarding flow, securely hand the activation token to the invited
person. For this local walkthrough, activate the resident directly:

```bash
read -r -s -p 'Resident password, at least 12 characters: ' RESIDENT_PASSWORD; printf '\n'
jq -nc --arg token "$ACTIVATION_TOKEN" --arg password "$RESIDENT_PASSWORD" \
  '{token:$token,password:$password}' |
  curl -fsS "$API_BASE/api/v1/auth/activate" \
    -H 'Content-Type: application/json' --data-binary @-

RESIDENT_SESSION=$(jq -nc \
  --arg tenantId "$TENANT_ID" --arg email "$RESIDENT_EMAIL" \
  --arg password "$RESIDENT_PASSWORD" \
  '{tenantId:$tenantId,email:$email,password:$password,device:"Resident walkthrough"}' |
  curl -fsS "$API_BASE/api/v1/auth/login" \
    -H 'Content-Type: application/json' --data-binary @-)
RESIDENT_TOKEN=$(printf '%s' "$RESIDENT_SESSION" | jq -er .accessToken)
```

Activation and recovery completion return `{"accepted":true}`. Sign in
separately to obtain session tokens. An invitation key is accepted only once;
retrying an already successful invitation returns `409` without another token.

## 3. Receive a delivery, issue a credential, and confirm pickup

The administrator acts as authorized reception staff in this example. Regular
concierge accounts use the narrower `packages.receive` and `packages.collect`
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
