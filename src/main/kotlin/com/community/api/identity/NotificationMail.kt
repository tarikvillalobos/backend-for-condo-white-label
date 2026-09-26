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
