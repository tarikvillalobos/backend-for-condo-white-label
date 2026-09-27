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
        for (record in rows) {
            val logical = record.logical()
            val eligible = filters.all { (k,v) -> logical.data.string(k) == v } && predicate(logical)
            if (eligible && result.size == limit) { hasMore = true; break }
            lastCreated = record.createdAt; lastId = record.id
            if (eligible) result += transform(logical)
        }
        if (hasMore || rows.size < 200) break
    }
    return obj("items" to result, "page" to obj(
        "nextCursor" to if (hasMore) cursor(snapshot.copy(lastCreated = lastCreated, lastId = lastId)) else null,
        "snapshotAt" to microInstant(snapshot.at), "snapshotExpiresAt" to microInstant(snapshot.expires),
    ))
}

private fun V1Context.snapshot(binding: String): Snapshot {
    val cursor = query["cursor"]
    if (cursor != null) {
        val parts = cursor.split('.')
        if (parts.size != 2 || !Secrets.verifies(parts[0], parts[1])) fail(422, "VALIDATION_ERROR", "Invalid cursor")
        val decoded = runCatching { json.parseToJsonElement(Base64.getUrlDecoder().decode(parts[0]).toString(Charsets.UTF_8)).jsonObject }
            .getOrElse { fail(422, "VALIDATION_ERROR", "Invalid cursor") }
        return tx.connection.prepareStatement("SELECT * FROM v1_snapshots WHERE id = ? AND tenant_id = ? AND brand_id = ? AND fingerprint = ?").use {
            listOf(decoded.string("id"), tenantId, brandId, binding).forEachIndexed { index, value -> it.setString(index + 1, value) }
            it.executeQuery().use { rows ->
                if (!rows.next()) fail(410, "CURSOR_EXPIRED", "Cursor is unavailable for this context")
                val expires = rows.getLong("expires_at")
                if (expires <= store.micros()) fail(410, "CURSOR_EXPIRED", "Snapshot expired; restart pagination")
                Snapshot(rows.getString("id"), rows.getLong("snapshot_at"), expires, decoded.string("created") ?: "", decoded.string("last") ?: "")
            }
        }
    }
    val at = store.micros()
    val snapshot = Snapshot(UUID.randomUUID().toString(), at, at + 900_000_000)
    tx.connection.prepareStatement("INSERT INTO v1_snapshots (id,tenant_id,brand_id,fingerprint,snapshot_at,expires_at) VALUES (?,?,?,?,?,?)").use {
        listOf(snapshot.id, tenantId, brandId, binding, at, snapshot.expires).forEachIndexed { index, value -> it.setObject(index + 1, value) }
        it.executeUpdate()
    }
    return snapshot
}
private fun cursor(snapshot: Snapshot): String {
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(obj("id" to snapshot.id, "created" to snapshot.lastCreated, "last" to snapshot.lastId).toString().toByteArray())
    return "$payload.${Secrets.sign(payload)}"
}
private fun microInstant(value: Long): String = Instant.ofEpochSecond(value / 1_000_000, value % 1_000_000 * 1000).toString()

fun V1Context.pageRecords(records: List<Record>, transform: (Record) -> JsonElement): JsonObject = pageItems(records.map(transform))
fun V1Context.pageItems(items: List<JsonElement>): JsonObject {
    // Materialized snapshots are reserved for small derived collections, not entity tables.
    if (items.size > 5000) fail(413, "RESULT_TOO_LARGE", "Use a narrower filter")
    val key = "derived:$operationId:${path.toSortedMap()}:${query.filterKeys { it != "cursor" }.toSortedMap()}:${principal?.userId}"
    val id = query["cursor"]?.let { unseal(it).split(':') } ?: listOf(UUID.randomUUID().toString(), "0")
    if (id.size != 2) fail(422, "VALIDATION_ERROR", "Invalid cursor")
    val record = if (query["cursor"] == null) store.create("derived_snapshot", obj("binding" to hash(key), "items" to items, "expiresAt" to now.plusSeconds(900)), ownerId = principal?.userId, id = id[0])
        else store.find("derived_snapshot", id[0]) ?: fail(410, "CURSOR_EXPIRED", "Snapshot expired")
    if (record.data.string("binding") != hash(key) || Instant.parse(record.data.string("expiresAt")).isBefore(now)) fail(410, "CURSOR_EXPIRED", "Snapshot expired")
    val offset = id[1].toIntOrNull() ?: fail(422, "VALIDATION_ERROR", "Invalid cursor")
    val limit = query["limit"]?.toIntOrNull() ?: 20
    if (offset < 0 || limit !in 1..100) fail(422, "VALIDATION_ERROR", "Invalid pagination")
    val saved = record.data["items"]!!.jsonArray
    return obj("items" to saved.drop(offset).take(limit), "page" to obj("nextCursor" to if (offset + limit < saved.size) seal("${record.id}:${offset + limit}") else null, "snapshotAt" to record.createdAt, "snapshotExpiresAt" to record.data["expiresAt"]))
}
