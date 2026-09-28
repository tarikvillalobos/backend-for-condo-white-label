package com.community.api.v1

import com.community.api.core.Database
import kotlinx.serialization.json.jsonObject
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
                    val id = data.string("_id")
                    if (brand != null && id != null) add(Triple(rows.getString("tenant_id"),brand,id))
                }
            } }
        }
    }
    var removed = 0
    for ((tenant,brand,id) in candidates) {
        val cleaned = db.scopedTx("file-cleanup:$tenant:$brand:$id") { tx ->
            val store = V1Store(tx,tenant,brand)
            val file = store.find("upload",id) ?: return@scopedTx false
            val used = tx.connection.prepareStatement("SELECT 1 FROM app_records WHERE tenant_id = ? AND kind <> 'v1_upload' AND payload LIKE ? LIMIT 1").use {
                it.setString(1,tenant); it.setString(2,"%$id%")
                it.executeQuery().use { result -> result.next() }
            }
            if (used) return@scopedTx false
            if (runCatching { UUID.fromString(id) }.isFailure) return@scopedTx false
            val directory = Path.of(System.getenv("UPLOAD_DIRECTORY") ?: "data/uploads").toAbsolutePath()
            Files.deleteIfExists(directory.resolve(id))
            store.update(file, kotlinx.serialization.json.JsonObject(file.data + obj("_deletedAt" to Instant.now(),"status" to "expired")))
            true
        }
        if (cleaned) removed++
    }
    return removed
}
