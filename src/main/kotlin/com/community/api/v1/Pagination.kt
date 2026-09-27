package com.community.api.v1

import com.community.api.core.Record
import com.community.api.core.json
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.Base64
import java.util.UUID

private data class Snapshot(val id: String, val at: Long, val expires: Long, val lastCreated: String = "", val lastId: String = "")

fun V1Context.page(
    kind: String,
    locationId: String? = this.locationId,
    ownerId: String? = null,
    filters: Map<String, String> = emptyMap(),
    predicate: (Record) -> Boolean = { true },
    transform: (Record) -> JsonElement,
): JsonObject {
    val limit = query["limit"]?.toIntOrNull() ?: 20
    if (limit !in 1..100) fail(422, "VALIDATION_ERROR", "Limit must be between 1 and 100")
    val binding = hash(obj("operation" to operationId, "path" to path, "query" to query.filterKeys { it != "cursor" }, "actor" to principal?.userId, "device" to principal?.deviceId, "kind" to kind, "location" to locationId, "owner" to ownerId, "filters" to filters).toString())
    val snapshot = snapshot(binding)
    var lastCreated = snapshot.lastCreated
    var lastId = snapshot.lastId
    val result = mutableListOf<JsonElement>()
    var hasMore = false
    while (result.size <= limit) {
        val values = mutableListOf<Any>(tenantId, brandId, store.prefix(kind), snapshot.at, snapshot.at)
        val sql = StringBuilder("SELECT * FROM v1_record_versions WHERE tenant_id = ? AND brand_id = ? AND kind = ? AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?) AND deleted = FALSE")
        if (locationId != null) { sql.append(" AND location_id = ?"); values += locationId }
        if (ownerId != null) { sql.append(" AND owner_id = ?"); values += ownerId }
        if (tx.postgres) filters.forEach { (key, value) -> sql.append(" AND payload::jsonb ->> ? = ?"); values += key; values += value }
        sql.append(" AND (created_at > ? OR (created_at = ? AND id > ?)) ORDER BY created_at, id LIMIT 200")
        values.addAll(listOf(lastCreated, lastCreated, lastId))
        val rows = tx.connection.prepareStatement(sql.toString()).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.v1Record()) } }
        }
        if (rows.isEmpty()) break
