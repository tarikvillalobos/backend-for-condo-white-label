# Community and operations API

These endpoints use bearer authentication, tenant isolation, active memberships,
location features, and explicit action permissions. All mutations and their audit
records run in a database transaction. Lists use `offset` and `limit` (1–200).

Except notifications, paths below start with `/api/v1/locations/{locationId}`.
JSON request shapes are Kotlin serializable classes in `community`. Timestamps are
ISO-8601 instants with UTC offsets normalized by clients to `Z`. Local scheduling
should use the location's configured time zone before sending an instant.

## Announcements

- `GET /announcements`: published, unexpired notices visible to the current unit;
  publishers can also see scheduled and archived notices. Pinned notices come first.
- `POST /announcements`, `PUT /announcements/{id}`: `AnnouncementInput` with
  `title`, `message`, optional `unitId`, `publishAt`, `expiresAt`, `pinned`,
  `priority` (`normal`, `high`, `urgent`), `attachments`, and `acknowledgmentRequired`.
- `POST /announcements/{id}/archive`: archive a notice.
- `POST /announcements/{id}/read`: persist one receipt per person and notice.
- `GET /announcements/{id}/receipts`: publisher access to receipts.

Permissions: `announcements.read`, `announcements.manage`. Read receipts are
explicit acknowledgments; delivery does not imply reading. Publication windows
are evaluated on every read, so scheduled notices do not need a publishing job.

## Events

- `GET /events`, `POST /events`, `PUT /events/{id}`: list, publish, and update.
  Updates cannot lower capacity below confirmed attendance.
- `POST /events/{id}/cancel`: cancel an event.
- `POST /events/{id}/attendance`, `DELETE /events/{id}/attendance`: register or
  cancel the caller's attendance. Repeated registration returns the same record.
- `GET /events/{id}/attendance`: own registration; publishers can list attendees.

`EventInput` requires `title`, `description`, `startsAt`, `endsAt`, and `capacity`
(1–10,000). Registration closes at the start and rejects cancellation or full
capacity. Transactions prevent concurrent registrations from overbooking.
Permissions: `events.read`, `events.manage`, `events.attend`.

