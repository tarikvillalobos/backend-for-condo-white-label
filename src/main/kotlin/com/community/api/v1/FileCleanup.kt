package com.community.api.v1

import com.community.api.core.Database
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

fun processFileCleanup(db: Database): Int {
    val cutoff = Instant.now().minusSeconds(86400)
    val candidates = db.scopedTx(null) { tx ->
        val sql = "SELECT tenant_id,payload,created_at FROM app_records WHERE kind = 'v1_upload' AND created_at < ? " +
            "AND payload NOT LIKE '%\"_deletedAt\"%' ORDER BY created_at LIMIT 100"
        tx.connection.prepareStatement(sql).use { statement ->
            statement.setString(1, cutoff.toString())
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) {
                    val data = com.community.api.core.json.parseToJsonElement(rows.getString("payload")).jsonObject
                    val brand = data.string("_brandId")
