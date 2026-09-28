> Documento histórico da API `/api/v1`. Consulte o [OpenAPI atual](openapi.yaml) e o [guia operacional](operations.md) para usar `/v1`.

# Identity and account API

All requests and responses use JSON. Account identities are scoped to `tenantId`; the same email in another client is a separate account. Authorization uses `Authorization: Bearer <accessToken>`.

## Authentication

| Method and path | Request | Behavior |
| --- | --- | --- |
| `POST /api/v1/auth/login` | `tenantId`, `email`, `password`, optional `device` | Issues access and refresh tokens. |
| `POST /api/v1/auth/refresh` | `refreshToken` | Rotates both tokens; the old access token immediately stops working. |
| `POST /api/v1/auth/activate` | `token`, `password` | Consumes an administrator invitation and activates the account. |
| `POST /api/v1/auth/recovery/request` | `tenantId`, `email` | Returns the same acknowledgment for existing and unknown accounts. |
| `POST /api/v1/auth/recovery/confirm` | `token`, `password` | Consumes a recovery token and revokes all existing sessions and challenges. |
| `POST /api/v1/auth/otp/request` | `tenantId`, `email` | Queues a code when the client's `otpLogin` policy is enabled. |
| `POST /api/v1/auth/otp/confirm` | `tenantId`, `email`, `code`, optional `device` | Consumes a valid code and issues tokens. |
| `POST /api/v1/auth/logout` | Authenticated, no body | Revokes the current session. |

Passwords require 12–256 characters and are stored with PBKDF2-HMAC-SHA256, 600,000 iterations, independent random salts, and constant-time hash comparison. Password login defaults to enabled; OTP defaults to disabled. Login, recovery, verification, and renewal have persistent per-account and per-source rate limits, with fixed hash buckets to bound storage. Source addresses come from the actual connection, so forwarding headers cannot spoof the rate-limit identity. Configure a trusted proxy explicitly before adding proxy-aware extraction.

Access tokens expire after 15 minutes. Refresh tokens expire after 30 days from the original login. All tokens contain 256 random bits; only hashes are stored in session records. Refresh is single-use: replaying a previously consumed token revokes that session, including its replacement tokens. Clients must serialize refresh calls and discard the old pair after success. There are at most 20 unrevoked sessions per account; the oldest sessions are revoked when necessary. Client and account activation are checked on every authenticated request.

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
