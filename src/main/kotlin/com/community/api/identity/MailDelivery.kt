package com.community.api.identity

import com.community.api.core.*
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.UUID

data class MailBatchResult(val sent: Int, val failed: Int)

suspend fun deliverAuthMailBatch(db: Database, config: MailConfig, sender: MailSender = SmtpMailSender): MailBatchResult {
    var sent = 0
    var failed = 0
    repeat(20) {
        val claimed = db.query { it.claimAuthDelivery() } ?: return MailBatchResult(sent, failed)
        val data = claimed.decode<AuthDelivery>()
        val current = db.query { tx ->
            tx.get("auth_delivery", claimed.id, claimed.tenantId)?.decode<AuthDelivery>()?.leaseId == data.leaseId
        }
        if (!current) return@repeat
        try {
            sender.send(config, data.asMessage(claimed.id))
            db.query { tx ->
                val record = tx.get("auth_delivery", claimed.id, claimed.tenantId)
                if (record != null && record.decode<AuthDelivery>().leaseId == data.leaseId) tx.delete(record)
            }
            sent++
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            db.query { tx ->
                val record = tx.get("auth_delivery", claimed.id, claimed.tenantId)
                if (record != null && record.decode<AuthDelivery>().leaseId == data.leaseId) {
                    val exhausted = data.attempts >= 5
                    tx.update(record, body(data.copy(leaseId = null, leaseUntil = null,
                        status = if (exhausted) "failed" else "pending", lastFailure = "smtp_delivery_failed",
                        nextAttemptAt = Instant.now().plusSeconds(minOf(300L, 30L shl (data.attempts - 1))).toString())))
                }
            }
            failed++
        }
    }
    return MailBatchResult(sent, failed)
}

internal fun Tx.claimAuthDelivery(): Record? {
    val now = Instant.now()
    for (client in clients()) {
        for (record in list("auth_delivery", client.id)) {
            val data = record.decode<AuthDelivery>()
            val challenge = record.ownerId?.let { get("auth_challenge", it, record.tenantId) }?.decode<ChallengeData>()
            if (expired(data.expiresAt) || challenge == null || challenge.consumed || !tenantAvailable(record.tenantId)) {
                delete(record)
                continue
            }
            if (data.status == "failed" || data.nextAttemptAt?.let { Instant.parse(it).isAfter(now) } == true ||
                data.leaseUntil?.let { Instant.parse(it).isAfter(now) } == true) continue
            if (data.attempts >= 5) {
                update(record, body(data.copy(status = "failed", leaseId = null, leaseUntil = null, lastFailure = "delivery_attempts_exhausted")))
                continue
            }
            return update(record, body(data.copy(attempts = data.attempts + 1,
