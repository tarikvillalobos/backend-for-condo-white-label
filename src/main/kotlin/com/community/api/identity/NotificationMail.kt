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
