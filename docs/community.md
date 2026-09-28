# Comunidade e operação v1

O [OpenAPI](openapi.yaml) define os schemas, as permissões e os códigos de cada operação. O morador acessa recursos pelo prefixo `/v1/memberships/{membershipId}`; a equipe usa `/v1/admin/condominiums/{condominiumId}` ou `/v1/ops`. O servidor confirma o vínculo ativo e aplica o escopo de condomínio e de módulo em cada chamada.

## Comunicação e atendimento

Avisos, eventos, notificações, documentos e contatos têm rotas próprias. Notificações internas persistem independentemente da entrega externa; `readAt` é gravado quando o usuário lê. Avisos podem ser agendados, e um worker processa a entrega. Chamados e ocorrências mantêm estado, comentários e histórico auditável; comentários internos exigem acesso de equipe. Relatórios e exportações podem ser processados em segundo plano e baixados por URL assinada.

## Acesso e portaria

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
- `POST /requests/{id}/escalate`: `{reason,priority,userId?,dueAt?}`; staff may
  increase or retain priority, optionally reassign, and set a deadline.
- `GET /requests/{id}/escalations`: staff-only escalation reasons and change history.
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
Only open/in-progress requests can be escalated; resolved/closed requests must be
reopened first. Escalation preserves lifecycle status and never lowers priority.
An optional assignee must have scoped management access. The current priority,
assignee, and deadline are visible to the owner; escalation reasons and prior
assignment details remain in staff-only history. Inbox updates contain generic
text and omit private escalation reasons. Escalation and its history are atomic.

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

### Concierge handover and incident notes

- `GET /shift-notes`, `POST /shift-notes`: `{message,incident:false}`.

Both operations require the explicit `concierge.notes` permission and the
`visitors` feature. The default concierge and manager roles can participate;
residents cannot publish or read these records. Notes are immutable, scoped to
their location, timestamped, and attributed to their author. Text allows up to
10,000 characters. The incident flag identifies an operational incident without
publishing a resident-visible request or sending private handover text in notifications.

## Vehicles and parking

- `GET /vehicles`, `POST /vehicles`, `PUT /vehicles/{id}`, `DELETE /vehicles/{id}`.
- `GET /vehicles/{id}/movements`, `POST /vehicles/{id}/movements`:
  `{direction:"entry"}` or `{direction:"exit"}`.
- `GET /parking`, `POST /parking`, `PUT /parking/{id}`.

`VehicleInput`: `plate`, `model`, `color`, optional `unitId`, `validUntil`.
Plates are normalized and unique per location. Resident visibility is restricted
to owned vehicles, movements, and allocated spaces. `ParkingInput` has `name`
and nullable `vehicleId`; setting null releases the allocation. Allocated
vehicles and vehicles currently checked in cannot be deleted. Staff enter movements with sequence validation;
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

Paths here start with `/api/v1/notifications`:

- `GET /`, `GET /unread-count`, `POST /{id}/read`.
- `GET /preferences`, `PUT /preferences`: `{push,email,sms,language}`; languages
  are `pt-BR`, `en`, and `es`.

Each user can access only their own inbox. Removed memberships and disabled
location features hide related messages immediately. Marking a message read is
idempotent. Preferences persist independently of external channel availability.
Push/SMS delivery requires provider setup; an inbox entry is not proof of external
delivery. Permissions: `notifications.read`, `notifications.manage`.

## Cameras and provider boundaries

- `GET /cameras`, `POST /cameras`, `PUT /cameras/{id}`:
  `{name,area,enabled,unitId?}`.
- `POST /cameras/{id}/sessions` requires `cameras.view` and the camera audience.
- `GET /cameras/{id}/recordings` separately requires `cameras.recordings`.

Both provider operations return `501 integration_unavailable` until a real
provider adapter is implemented. They never return invented live URLs or
equipment credentials. Management requires `cameras.manage`.

## Validation and boundaries

Titles generally allow 160 characters, long descriptions 10,000, and attachment
lists at most 10 URLs. External URLs require HTTPS and reject embedded credentials.
Invalid bodies produce 400, unauthorized operations 403, out-of-scope identifiers
404, and invalid state transitions 409. Read-own permission does not authorize
reading another member's resources or adding internal staff comments.
These modules store metadata and auditable staff operations; provider-specific
delivery, live video, gate actuation, and external attachment access policies are
separate integration responsibilities.
