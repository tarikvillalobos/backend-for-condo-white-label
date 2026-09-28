package com.community.api.v1.community

import com.community.api.core.Database
import com.community.api.core.json
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

private data class DueAnnouncement(val tenant: String, val brand: String, val location: String, val id: String, val author: String?)
fun processCommunityNotifications(db: Database) {
    val pending = db.scopedTx(null) { tx ->
        val filter = if (tx.postgres) " AND payload::jsonb ->> 'pushNotify' = 'true' AND payload::jsonb ->> 'notifiedAt' IS NULL AND payload::jsonb ->> '_deletedAt' IS NULL AND (payload::jsonb ->> 'publishedAt')::timestamptz <= CURRENT_TIMESTAMP"
            else " AND payload LIKE '%\"pushNotify\":true%' AND payload LIKE '%\"notifiedAt\":null%'"
        tx.connection.prepareStatement("SELECT * FROM app_records WHERE kind = 'v1_announcement'$filter ORDER BY created_at LIMIT 500").use { statement ->
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) {
                    val data = json.parseToJsonElement(rows.getString("payload")).jsonObject
                    if (data.text("_deletedAt") == null && data.flag("pushNotify") && data.text("notifiedAt") == null && !timestamp(data.text("publishedAt")!!).isAfter(Instant.now()))
                        add(DueAnnouncement(rows.getString("tenant_id"), data.text("_brandId")!!, rows.getString("location_id"), data.text("_id")!!, rows.getString("owner_id")))
                }
            } }
        }
    }
    pending.forEach { job -> db.scopedTx("community-notification:${job.id}") { tx ->
        val c = V1Context(tx, "publishScheduledAnnouncement", job.tenant, job.brand, UUID.randomUUID().toString(),
            principal = V1Principal(userId = job.author, staff = true), locationId = job.location)
        val row = c.store.find("announcement", job.id, job.location) ?: return@scopedTx
        if (row.data.text("notifiedAt") != null || timestamp(row.data.text("publishedAt")!!).isAfter(c.now)) return@scopedTx
        if (row.data.text("expiresAt")?.let { timestamp(it).isAfter(c.now) } != false)
            c.broadcast("announcement", row.id, row.data.text("title")!!, row.data.text("body"), row.data.array("targetNodeIds"))
        val updated = c.store.update(row, row.data.merge(obj("notifiedAt" to c.now.toString())))
        c.audit("announcement.notifications_created", updated)
    } }
}
