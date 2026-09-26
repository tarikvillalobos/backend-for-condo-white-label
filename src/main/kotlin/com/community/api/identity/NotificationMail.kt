package com.community.api.identity

import com.community.api.community.NotificationPreferences
import com.community.api.community.notificationContext
import com.community.api.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
internal data class NotificationEmailDelivery(
    val status: String = "pending", val attempts: Int = 0, val nextAttemptAt: String? = null,
    val leaseId: String? = null, val leaseUntil: String? = null, val lastFailure: String? = null,
    val acceptedAt: String? = null,
)

@Serializable
data class NotificationDeliveryStatus(val status: String, val attempts: Int, val acceptedAt: String?, val failure: String?)

fun Tx.notificationMailStatus(notification: Record): NotificationDeliveryStatus? =
    get("notification_delivery", notificationDeliveryId(notification.id), notification.tenantId)
        ?.decode<NotificationEmailDelivery>()?.let { NotificationDeliveryStatus(it.status, it.attempts, it.acceptedAt, it.lastFailure) }

suspend fun deliverNotificationMailBatch(db: Database, config: MailConfig, sender: MailSender = SmtpMailSender): MailBatchResult {
    var sent = 0
    var failed = 0
    repeat(20) {
        val claim = db.query { it.claimNotificationDelivery() } ?: return MailBatchResult(sent, failed)
        val destination = db.query { it.recheckNotificationDelivery(claim) } ?: return@repeat
        try {
            sender.send(config, MailMessage(destination, "Community: new notification",
                "You have a new notification. Open the app to view it.", claim.id))
            db.query { it.finishNotificationDelivery(claim, true) }
            sent++
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            db.query { it.finishNotificationDelivery(claim, false) }
            failed++
        }
    }
    return MailBatchResult(sent, failed)
}

internal fun notificationDeliveryId(id: String): String = "notification-mail:$id"

internal fun Tx.notificationEmail(notification: Record): String? {
    val userId = notification.ownerId ?: return null
    val account = get("account", userId, notification.tenantId)?.decode<Account>() ?: return null
    if (!account.active) return null
    val preferences = list("notification_preferences", notification.tenantId, ownerId = userId)
        .firstOrNull()?.decode<NotificationPreferences>() ?: NotificationPreferences()
    if (!preferences.email) return null
    try { notificationContext(Actor(userId, notification.tenantId, "mail-worker"), notification.locationId, "notifications.read") }
    catch (failure: ApiException) { if (failure.status in setOf(403, 404)) return null else throw failure }
    return account.email
}

internal fun Tx.claimNotificationDelivery(): Record? {
    val now = Instant.now()
    for (client in clients()) {
        for (notification in list("notification", client.id)) {
            val id = notificationDeliveryId(notification.id)
            val existing = get("notification_delivery", id, notification.tenantId)
            val data = existing?.decode<NotificationEmailDelivery>() ?: NotificationEmailDelivery()
            if (data.status != "pending" || data.nextAttemptAt?.let { Instant.parse(it).isAfter(now) } == true ||
                data.leaseUntil?.let { Instant.parse(it).isAfter(now) } == true) continue
            val status = when {
                notificationEmail(notification) == null -> "suppressed"
                data.attempts >= 5 -> "failed"
                else -> "pending"
            }
            val updated = if (status == "pending") data.copy(attempts = data.attempts + 1,
                leaseId = UUID.randomUUID().toString(), leaseUntil = now.plusSeconds(300).toString())
            else data.copy(status = status, leaseId = null, leaseUntil = null)
            val row = if (existing == null) create("notification_delivery", notification.tenantId, notification.locationId,
                notification.ownerId, body(updated), id) else update(existing, body(updated))
            if (status == "pending") return row
        }
    }
