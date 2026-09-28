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
