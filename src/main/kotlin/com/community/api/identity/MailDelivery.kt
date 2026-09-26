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
