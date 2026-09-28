package com.community.api.v1.identity

import com.community.api.core.*
import com.community.api.identity.*
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

suspend fun processIdentityDataRequests(db: Database): Int {
    val pending = db.query { tx ->
        tx.connection.prepareStatement("SELECT * FROM app_records WHERE kind = 'v1_data_request' " +
            "AND payload LIKE '%\"status\":\"received\"%' AND payload LIKE '%\"kind\":\"deletion\"%' " +
            "ORDER BY created_at LIMIT 100").use { statement ->
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.v1Record()) } }
        }
    }
    var completed = 0
    for (raw in pending) {
        val executeAfter = raw.data.string("executeAfter")?.let(Instant::parse) ?: continue
        if (executeAfter.isAfter(Instant.now())) continue
        val deleted = db.query { tx ->
            val brandId = raw.data.string("_brandId") ?: return@query false
            val userId = raw.ownerId ?: return@query false
            val c = V1Context(tx, "completeDataDeletion", raw.tenantId, brandId, UUID.randomUUID().toString(),
                principal = V1Principal(actor = Actor(userId, raw.tenantId, "privacy-worker")))
            val request = c.store.find("data_request", raw.data.string("_id") ?: raw.id) ?: return@query false
            if (request.data.string("status") != "received") return@query false
            if (c.store.list("retention_hold").any { it.data.string("releasedAt") == null }) return@query false
            c.completeIdentityDeletion(request)
            true
        }
        if (deleted) completed++
    }
    return completed
}

private fun V1Context.completeIdentityDeletion(request: Record) {
    store.list("session", ownerId = userId).forEach { revokeIdentitySession(it.id) }
    val personalKinds = listOf("profile", "membership", "staff_assignment", "vehicle", "pet", "vaccination",
        "visitor", "access_invite", "reservation", "attendance", "ticket", "comment", "occurrence", "notification", "upload")
    for (kind in personalKinds) {
        store.list(kind, ownerId = userId).forEach { record ->
            val anonymous = obj("status" to "deleted", "anonymizedAt" to now.toString(), "_deletedAt" to now.toString())
            val updated = store.update(record, anonymous)
            tx.connection.prepareStatement("UPDATE v1_record_versions SET payload = ? WHERE id = ?").use {
                it.setString(1, updated.data.toString())
                it.setString(2, store.physicalId(record.kind, record.id))
                it.executeUpdate()
            }
        }
    }
    val otherBrands = listOf("v1_membership", "v1_staff_assignment").any { kind ->
        tx.list(kind, tenantId, ownerId = userId).any {
            it.data.string("_brandId") != brandId && it.data.string("status") == "active" && it.data.string("_deletedAt") == null
        }
    }
    if (!otherBrands) {
        val user = account()
        tx.update(user, body(Account("", "Conta removida", "", false)))
        tx.list("v1_identifier", tenantId, ownerId = userId).forEach { tx.delete(it) }
        tx.revokeSessions(tenantId, userId)
        consumeOtherChallenges(userId)
    }
    val completed = store.update(request, request.data.with("status" to "completed", "completedAt" to now.toString()))
    audit("privacy.deletion_completed", completed)
}
