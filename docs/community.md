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

## Pets and lost notices

- `GET /pets`, `POST /pets`, `PUT /pets/{id}`, `DELETE /pets/{id}`.
- `GET /lost-pets`, `POST /lost-pets`, `POST /lost-pets/{id}/resolve`.

`PetInput`: `name`, `species`, optional `unitId`, `identification`, `photoUrl`, and
`vaccinationUrls`. Unit association must belong to the caller unless a manager
performs the operation. Vaccination and owner records stay private.
`LostPetInput`: owned `petId`, public `message`, public `lastSeen`.
Lost-notice responses contain pet name/species/photo and public text; they omit
owner identifiers, units, identification numbers, and vaccination attachments.

Permissions: `pets.read.own`, `pets.read.all`, `pets.create`, `pets.manage.own`,
`pets.manage`. Creation does not confer access to another person's pet.

## Requests, complaints, incidents, and support

- `GET /requests`, `POST /requests`, `GET /requests/{id}`.
- `POST /requests/{id}/assign`: `{userId,dueAt?}`; assignee must be an active
  member with scoped request-management access.
- `POST /requests/{id}/status`: `{status,reason}`.
- `GET /requests/{id}/comments`, `POST /requests/{id}/comments`:
  `{message,internal:false,attachments:[]}`.

`RequestInput`: `title`, `description`, `category` (`request`, `complaint`,
`incident`, `maintenance`, `support`), `priority` (`normal`, `high`, `urgent`),
and optional `attachments`.

Transitions: `open → in_progress/cancelled`; `in_progress → resolved/open/cancelled`;
`resolved → closed/open`; `closed → open`. Cancelled requests are terminal.
Owners may cancel open requests or reopen resolved/closed requests. Staff control
the remaining transitions. Staff-only comments require management permission and
are excluded from resident and read-only audit responses. Closed/cancelled
requests reject new comments. Assignment and status changes create inbox updates.

Permissions: `requests.create`, `requests.read.own`, `requests.read.all`,
`requests.comment`, `requests.manage`.

## Visitors

- `GET /visitors`, `POST /visitors`.
- `POST /visitors/{id}/revoke`.
- `POST /visitors/{id}/check-in`: `{admissionCode}`.
- `POST /visitors/{id}/check-out`.

`VisitorInput`: `name`, `purpose`, `validFrom`, `validUntil`, optional `unitId`,
`singleUse` (default true). Validity windows may span at most 90 days.
Creation returns `{invitation,admissionCode}`. Only a SHA-256 credential hash is
persisted; the admission code is returned once and is omitted from all lists.
Creation requires `Idempotency-Key` (8–128 characters: letters, digits, `_`, `.`,
`:`, or `-`). Reusing a key within the same user and location returns 409 without
creating another invitation or reissuing its code. If the first response was lost,
revoke the invitation and create a replacement with a new key.
Checking in requires staff permission, the code, current inviter membership,
an active validity window, and unused admission when single-use is enabled.
Checkout is separate from revocation. Physical gate commands are not implied.

Permissions: `visitors.create`, `visitors.read.own`, `visitors.read.all`,
`visitors.manage`, `visitors.checkin`.

## Vehicles and parking

- `GET /vehicles`, `POST /vehicles`, `PUT /vehicles/{id}`, `DELETE /vehicles/{id}`.
- `GET /vehicles/{id}/movements`, `POST /vehicles/{id}/movements`:
  `{direction:"entry"}` or `{direction:"exit"}`.
- `GET /parking`, `POST /parking`, `PUT /parking/{id}`.

`VehicleInput`: `plate`, `model`, `color`, optional `unitId`, `validUntil`.
Plates are normalized and unique per location. Resident visibility is restricted
to owned vehicles, movements, and allocated spaces. `ParkingInput` has `name`
and nullable `vehicleId`; setting null releases the allocation. Allocated
vehicles cannot be deleted. Staff enter movements with sequence validation;
expired vehicle authorization and inactive owner membership prevent entry.

Permissions: `vehicles.read.own`, `vehicles.read.all`, `vehicles.create`,
`vehicles.manage.own`, `vehicles.manage`.

## Staff, contractors, equipment, and work orders

- `GET /staff`, `POST /staff`: upsert `{userId,responsibility,active}`. A staff
  profile describes duties; it does not grant roles or access permissions.
- `GET /contractors`, `POST /contractors`, `PUT /contractors/{id}`:
  `{name,service,contact,approved}`.
- `GET /equipment`, `POST /equipment`, `PUT /equipment/{id}`:
  `{name,description,serialNumber?,nextInspectionAt?}`.
- `GET /work-orders`, `POST /work-orders`:
  `{title,description,assignedTo,scheduledAt,contractorId?,equipmentId?}`.
- `POST /work-orders/{id}/status`: `{status,notes,evidence:[]}`.
- `GET /work-orders/{id}/history`: retained status notes and completion evidence.

An assignee must be active and have scoped maintenance access; referenced
contractors must be approved. Workers see assigned work only. Managers see all
work in their location. Statuses follow `scheduled → in_progress → completed`;
managers may cancel active work or reopen completed work as scheduled. Notes
and evidence are stored with the current order, and each transition is audited.
Contractor registration does not issue gate credentials; visitor invitations
handle temporary admission separately.

Permissions: `staff.manage`, `maintenance.read`, `maintenance.work`, `maintenance.manage`.

## Documents and contacts

- `GET /documents`, `POST /documents`.
- `GET /documents/{id}/versions`, `POST /documents/{id}/versions`.
- `POST /documents/{id}/acknowledge`, `GET /documents/{id}/acknowledgments`.
- `POST /documents/{id}/archive`.
- `GET /contacts`, `POST /contacts`, `PUT /contacts/{id}`, `DELETE /contacts/{id}`.

`DocumentInput`: `title`, `description`, `url`, `mediaType`, optional `unitId`,
`acknowledgmentRequired`. Versions are append-only, individually audience-checked,
and acknowledgments refer to a specific revision. Supported metadata MIME types:
PDF, JPEG, PNG, plain text, and DOCX. External document URLs must use HTTPS;
the storage provider must enforce any required access policy for those URLs.
`ContactInput`: `name`, `category` (`emergency`, `administration`, `maintenance`,
`service`), optional `phone`, `email`, `website`, `operatingHours`. At least one
contact method is required. Contacts are deliberately published by managers.

Permissions: `documents.read`, `documents.manage`, `contacts.read`, `contacts.manage`.

## Notification inbox and preferences
