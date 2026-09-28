# Identidade e sessões v1

O fluxo completo está no [OpenAPI](openapi.yaml), nas seções Authentication e Profile. Todas as chamadas de negócio enviam `X-Brand-Id`; a autenticação também depende da marca. A primeira conta administrativa é criada pelo comando `bootstrap` descrito no [README](../README.md).

## Entrar e renovar

- `POST /v1/auth/password/login` recebe `identifier` e `password` e devolve tokens de acesso e renovação.
- `POST /v1/auth/challenges` inicia o acesso por código. `POST /v1/auth/challenges/{challengeId}/verify` conclui o desafio. O Compose entrega e-mail no Mailpit; outros canais dependem de provedor configurado.
- `POST /v1/auth/refresh` renova a sessão; `POST /v1/auth/logout` a encerra. Rotação e revogação invalidam credenciais antigas.
- Equipe que precisa de segundo fator usa `POST /v1/auth/mfa/{challengeId}/verify`, conforme a resposta de autenticação e as exigências do contrato.

Use `Authorization: Bearer <accessToken>` nas rotas protegidas. `GET /v1/me/contexts`, `/v1/me/memberships` e `/v1/me/staff-assignments` mostram os contextos disponíveis. Permissão de equipe e acesso de morador são avaliados pelo vínculo e pelo escopo de cada requisição.

## Convites, conta e segurança

Convites são consultados em `GET /v1/auth/invitations/{code}` e aceitos em `POST /v1/auth/invitations/{code}/accept`. Uma conta já autenticada vincula outro convite em `POST /v1/me/invitations/{code}/link`. Alterações de senha, recuperação, troca de contato e preferências têm rotas próprias no contrato.

## Profile and sessions

| Method and path | Request or behavior |
| --- | --- |
| `GET /api/v1/me` | Sanitized account ID, client ID, email, and name. |
| `PATCH /api/v1/me` | `{ "name": "New name" }`. |
| `POST /api/v1/me/password` | `currentPassword`, `newPassword`; revokes all sessions and pending security challenges. |
| `POST /api/v1/me/contact/request` | New `email` and current `password`; delivers verification to the new address. |
| `POST /api/v1/me/contact/confirm` | `token`; requires the same authenticated account and revokes sessions after confirmation. |
| `POST /api/v1/me/verify` | `password`; renews the 10-minute verification window for privileged actions. |
| `GET /api/v1/me/sessions` | Active session IDs, device labels, creation/expiration dates, and current-session indicator. |
| `DELETE /api/v1/me/sessions` | Revokes all sessions. |
| `DELETE /api/v1/me/sessions/{id}` | Revokes an owned session; other users' sessions are unavailable. |

Invitation tokens expire after 72 hours, recovery and contact tokens after 30 minutes, and OTPs after five minutes. OTPs permit at most five guesses. Issuing a replacement challenge invalidates the previous challenge of that type. Administrator account deactivation must call `Tx.revokeAccountCredentials` to invalidate outstanding invitations as well as sessions.

## Credential delivery boundary

Recovery, contact-verification, and OTP credentials are written to private `auth_delivery` records. These records are excluded from ordinary notifications, reports, generic resources, and public API responses. The SMTP worker claims one delivery at a time with an atomic five-minute lease, releases the database transaction before network access, and deletes the secret delivery record only after SMTP acceptance. Acknowledgment of a request means that the request was processed; it does not assert email delivery or that a person read it. Consuming or invalidating a challenge removes any remaining queued delivery containing its credential.

Configure `SMTP_HOST`, `SMTP_PORT` (default 587), `SMTP_FROM`, `SMTP_USER`, `SMTP_PASSWORD`, and `SMTP_STARTTLS` (default true). Production startup requires authenticated SMTP with STARTTLS; certificate trust and host names are checked. SMTP settings must come from deployment secrets. Development may omit SMTP, leaving messages queued for local inspection; a local mail server may explicitly use `SMTP_STARTTLS=false` without credentials. The adapter uses [Eclipse Angus Mail](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html), with connection, read, and write timeouts.

Failed attempts use increasing 30–300 second delays and stop after five claims. Failure records retain a safe status code rather than provider exception messages. Expired or invalidated messages are discarded. Operators should monitor worker failure counts and inspect private failed-delivery statuses, then correct SMTP configuration. A new user request can issue a replacement challenge. Delivery is at least once: an SMTP acceptance followed by a process crash can cause a repeat email with the same message ID and challenge. SMS is not configured by this adapter.

Protect the database, backups, and worker access because queued delivery records contain short-lived credentials. Invitation creation returns its credential once to an authorized administrator for manual delivery. No credential is added to audit event payloads. For programmatic worker hosting, invoke `deliverAuthMailBatch(database, mailConfig)` from a managed coroutine loop and cancel the loop during application shutdown; tests inject `MailSender` without contacting a provider.

Authentication failures use `401`; throttling uses `429`. Privileged operations requiring fresh verification use `403` with code `verification_required`. Account password/contact changes require a fresh login afterward. Device names are caller-provided labels, not a device authentication mechanism.

## Notification email

`deliverNotificationMailBatch` uses the same SMTP adapter to email a generic notice: “You have a new notification. Open the app to view it.” Email content excludes inbox titles, messages, pickup credentials, and other personal details. The destination is read from the active account immediately before sending. Client and location activation, notification feature availability, current membership, `notifications.read`, and the user's email preference are checked both when claiming work and immediately before SMTP submission.

Delivery status is stored separately in `notification_delivery`, with no email address or notification content copied into that record. Successful SMTP acceptance records `accepted` and a timestamp; it does not set the inbox `readAt` field. Subsequent scans do not resend accepted notifications. Opted-out or unauthorized notifications become `suppressed` and are not replayed if preferences or access change later. Failures retry up to five times with backoff and safe status codes. Concurrent workers use atomic five-minute leases; as with authentication email, a crash after SMTP acceptance can cause a duplicate delivery. `Tx.notificationMailStatus(notification)` exposes a sanitized status for an already-authorized notification.

`GET /api/v1/notifications/{id}/delivery` returns the owner's delivery status, attempt count, SMTP acceptance timestamp, and safe failure code. Current notification access is required even after a message was sent. An unscheduled notification returns `not_scheduled`; another user's notification is unavailable. Delivery status does not confirm that the email reached the recipient's inbox or was read.
