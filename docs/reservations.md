# Facilities and reservations

Routes use `/api/v1/locations/{locationId}`, a human bearer session, active
membership, and the `reservations` feature enabled for the client and location.
Every write runs in the same database transaction as authorization, audit, and
notifications. The database lock prevents two API instances from allocating the
same facility interval concurrently.

| Method and path | Permission and behavior |
| --- | --- |
| `GET /facilities` | `facilities.read`; facility rules |
| `POST /facilities` | `facilities.manage`; create facility |
| `PUT /facilities/{id}` | `facilities.manage`; update rules |
| `GET /facilities/{id}/availability?startsAt=...&endsAt=...` | `facilities.read`; busy intervals without resident identity |
| `GET /reservations` | `reservations.read.own` or `reservations.read.all`; scoped list |
| `GET /reservations/{id}` | Same; booking details and history |
| `POST /reservations` | `reservations.create`; requires `Idempotency-Key` |
| `POST /reservations/maintenance` | `reservations.manage`; requires `Idempotency-Key` |
| `POST /reservations/{id}/cancel` | Owner with `reservations.create`, or `reservations.manage` |
| `POST /reservations/{id}/approve` | `reservations.manage`; pending bookings only |
| `POST /reservations/{id}/reject` | `reservations.manage`; pending bookings only |

## Facility rules

```json
{
  "name": "Party room",
  "timeZone": "America/Sao_Paulo",
  "capacity": 40,
  "opensAt": "08:00",
  "closesAt": "22:00",
  "weekdays": [1, 2, 3, 4, 5, 6, 7],
  "maxDurationMinutes": 240,
  "minNoticeMinutes": 60,
  "maxDaysAhead": 90,
  "maxActivePerMember": 5,
  "requiresApproval": true,
  "maintenance": false
}
```

Provide the property's IANA time zone explicitly when creating a facility; the
default is `America/Sao_Paulo`. Weekdays use ISO numbering (Monday = 1). Local
operating hours must open and close on the same day. Bookings must also start
and finish within one local day. The server converts submitted UTC instants to
the facility zone, including daylight saving changes, before checking hours.

Each reservation exclusively occupies the facility for its interval. Capacity
is the maximum attendance for that booking, not a pool of separately bookable
seats. Minimum notice and duration use elapsed minutes. Advance limits use the
facility's local calendar date. Active limits are per member and facility.

Rule changes apply to subsequent bookings. Existing reservations remain recorded.
Before enabling whole-facility maintenance, staff must cancel future active
reservations. For a shorter closure, create a maintenance interval instead.

## Book, approve, and cancel

```json
{
  "facilityId": "facility-id",
  "startsAt": "2030-01-02T12:00:00Z",
  "endsAt": "2030-01-02T14:00:00Z",
  "attendees": 12,
  "note": "Family gathering"
}
```

An idempotency key is scoped to the member, location, and operation. Matching
retries return the existing reservation, including its current status. A changed
payload with the same key returns `409`. Pending approval reserves the interval
immediately; approval cannot overbook another reservation. Endpoints are
half-open intervals, so one booking may start exactly when another ends.

Statuses are `PENDING`, `CONFIRMED`, `MAINTENANCE`, `CANCELLED`, and `REJECTED`.
Pending, confirmed, and maintenance intervals block new reservations. Cancellation
and rejection release the interval while retaining history. Approval and rejection
require management permissions. Owners may cancel their own future or ongoing
bookings; already ended or closed bookings cannot transition again.

