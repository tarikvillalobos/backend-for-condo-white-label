# Comunidade e operação v1

O [OpenAPI](openapi.yaml) define os schemas, as permissões e os códigos de cada operação. O morador acessa recursos pelo prefixo `/v1/memberships/{membershipId}`; a equipe usa `/v1/admin/condominiums/{condominiumId}` ou `/v1/ops`. O servidor confirma o vínculo ativo e aplica o escopo de condomínio e de módulo em cada chamada.

## Comunicação e atendimento

Avisos, eventos, notificações, documentos e contatos têm rotas próprias. Notificações internas persistem independentemente da entrega externa; `readAt` é gravado quando o usuário lê. Avisos podem ser agendados, e um worker processa a entrega. Chamados e ocorrências mantêm estado, comentários e histórico auditável; comentários internos exigem acesso de equipe. Relatórios e exportações podem ser processados em segundo plano e baixados por URL assinada.

## Acesso e portaria

Convites de visitantes, chegadas e credenciais de acesso têm janelas de validade e estado. A portaria usa rotas `/v1/ops/access` para validar e registrar ações; leitores usam credenciais de dispositivo quando o contrato permitir. Revogação, uso único e vínculo ativo são checados pelo servidor. Um comando de acesso ou uma validação digital não prova entrada física sem evento confiável do equipamento.

## Moradores e bens

Pets, alertas, veículos, manutenção e reservas seguem o contexto do morador e as permissões do contrato. A equipe administra registros do condomínio nas rotas `/v1/admin/condominiums/{condominiumId}`. Fotos e documentos privados usam uploads e URLs assinadas; dados de proprietário e de saúde animal não devem ser copiados para notificações públicas.

## Câmeras e integrações

O cadastro de câmeras, a lista de gravações e sessões de vídeo dependem de um provedor real. Configure `CAMERA_PROVIDER_BASE_URL` e, se exigido pelo provedor, `CAMERA_PROVIDER_TOKEN`. O servidor solicita sessões temporárias e valida URL, prazo e protocolo retornados. Sem provedor, as operações de mídia respondem com erro explícito. O volume local de arquivos do Compose atende um único host; veja [operations.md](operations.md) para implantação distribuída.

Para testar os fluxos sem adivinhar campos, abra `/docs`, selecione a operação e use os schemas de request e response publicados. Os erros v1 usam `application/problem+json` com `requestId` para correlação.
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
