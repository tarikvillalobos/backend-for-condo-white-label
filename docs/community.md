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
