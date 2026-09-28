package com.community.api.v1.deliveries

import com.community.api.core.Database
import com.community.api.core.json
import com.community.api.v1.*
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

fun processParcelDeadlines(db: Database, now: Instant = Instant.now()): Int {
    val candidates = db.scopedTx(null) { tx ->
        val due = if (tx.postgres) "AND payload::jsonb ->> 'status' IN ('waiting','manual') " +
            "AND payload::jsonb ->> 'deadlineNearNotifiedAt' IS NULL AND payload::jsonb ->> '_deletedAt' IS NULL " +
            "AND (payload::jsonb ->> 'deadline')::timestamptz > ?::timestamptz " +
            "AND (payload::jsonb ->> 'deadline')::timestamptz <= ?::timestamptz" else ""
        val sql = "SELECT tenant_id,payload FROM app_records WHERE kind='v1_parcel' $due ORDER BY created_at" +
            if (tx.postgres) " LIMIT 200" else ""
        tx.connection.prepareStatement(sql).use { statement ->
            if (tx.postgres) { statement.setString(1,now.toString()); statement.setString(2,now.plusSeconds(86400).toString()) }
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) {
                    val data = json.parseToJsonElement(rows.getString("payload")).jsonObject
                    val brand = data.string("_brandId"); val id = data.string("_id")
                    if (brand != null && id != null) add(Triple(rows.getString("tenant_id"),brand,id))
                }
            } }
        }
    }
    var processed = 0
    for ((tenant,brand,id) in candidates) {
        processed += db.scopedTx("parcel-deadline:$tenant:$brand:$id") { tx ->
            val store = V1Store(tx,tenant,brand)
            val row = store.find("parcel",id) ?: return@scopedTx 0
            val deadline = row.data.string("deadline")?.let { Instant.parse(it) } ?: return@scopedTx 0
            if (row.data.string("status") !in setOf("waiting","manual") || row.data.string("deadlineNearNotifiedAt") != null ||
                !deadline.isAfter(now) || deadline.isAfter(now.plusSeconds(86400))) return@scopedTx 0
            val c = V1Context(tx,"parcelDeadlineReminder",tenant,brand,UUID.randomUUID().toString(),locationId=row.locationId,now=now)
            val updated = store.update(row,JsonObject(row.data + obj("deadlineNearNotifiedAt" to now)))
            c.notifyParcel(updated,"Prazo de retirada próximo")
            appendAudit(c,"parcel.deadline_near",updated)
            1
        }
    }
    return processed
}
