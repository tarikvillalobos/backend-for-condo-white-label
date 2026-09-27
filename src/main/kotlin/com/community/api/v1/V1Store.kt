package com.community.api.v1

import com.community.api.core.*
import kotlinx.serialization.json.*
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

class V1Store(val tx: Tx, val tenantId: String, val brandId: String) {
    fun find(kind: String, id: String, locationId: String? = null): Record? =
        tx.get(prefix(kind), physicalId(kind, id), tenantId)?.takeIf {
            it.data.string("_brandId") == brandId && it.data.string("_deletedAt") == null &&
                (locationId == null || it.locationId == locationId)
        }?.logical()
    fun get(kind: String, id: String, locationId: String? = null): Record = find(kind, id, locationId)
        ?: throw ApiException(404, "RESOURCE_NOT_FOUND", "Resource not found")

    fun list(kind: String, locationId: String? = null, ownerId: String? = null, filters: Map<String, String> = emptyMap()): List<Record> {
        val parameters = mutableListOf<Any>(prefix(kind), tenantId)
        val sql = StringBuilder("SELECT * FROM app_records WHERE kind = ? AND tenant_id = ?")
        if (locationId != null) { sql.append(" AND location_id = ?"); parameters += locationId }
        if (ownerId != null) { sql.append(" AND owner_id = ?"); parameters += ownerId }
        if (tx.postgres) {
            sql.append(" AND payload::jsonb ->> '_brandId' = ? AND payload::jsonb ->> '_deletedAt' IS NULL")
            parameters += brandId
            filters.forEach { (key, value) -> sql.append(" AND payload::jsonb ->> ? = ?"); parameters += key; parameters += value }
        }
        sql.append(" ORDER BY created_at, id")
        return tx.connection.prepareStatement(sql.toString()).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.v1Record()) } }
        }.filter { it.data.string("_brandId") == brandId && it.data.string("_deletedAt") == null && filters.all { (k,v) -> it.data.string(k) == v } }.map { it.logical() }
    }
    fun create(kind: String, data: JsonObject, locationId: String? = null, ownerId: String? = null, id: String = UUID.randomUUID().toString()): Record {
        val record = tx.create(prefix(kind), tenantId, locationId, ownerId, JsonObject(data + obj("_brandId" to brandId, "_id" to id)), physicalId(kind, id))
        if (!tx.postgres) history(record)
        return record.logical()
    }
    fun update(record: Record, data: JsonObject): Record {
        checkOwned(record)
        val physical = record.copy(id = physicalId(record.kind, record.id))
        val updated = tx.update(physical, JsonObject(data + obj("_brandId" to brandId, "_id" to record.id)))
        if (!tx.postgres) history(updated)
        return updated.logical()
    }
    fun delete(record: Record) { update(record, JsonObject(record.data + obj("_deletedAt" to Instant.now().toString()))) }
    private fun checkOwned(record: Record) {
        if (record.tenantId != tenantId || record.data.string("_brandId") != brandId) throw ApiException(404, "RESOURCE_NOT_FOUND", "Resource not found")
    }
    private fun history(record: Record) {
        val at = micros()
        tx.connection.prepareStatement("UPDATE v1_record_versions SET valid_to = ? WHERE id = ? AND valid_to IS NULL").use {
            it.setLong(1, at); it.setString(2, record.id); it.executeUpdate()
        }
        tx.connection.prepareStatement("INSERT INTO v1_record_versions (id,version,kind,tenant_id,location_id,owner_id,brand_id,payload,created_at,updated_at,valid_from,deleted,logical_id,sort_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use {
            listOf(record.id, record.version, record.kind, tenantId, record.locationId, record.ownerId, brandId, record.data.toString(), record.createdAt, record.updatedAt, at, record.data.string("_deletedAt") != null,
                record.data.string("_id") ?: record.id, record.data.string("depositedAt") ?: record.data.string("startsAt") ?: record.createdAt)
                .forEachIndexed { index, value -> it.setObject(index + 1, value) }
            it.executeUpdate()
        }
    }
    fun micros(): Long = if (tx.postgres) tx.connection.createStatement().use {
        it.executeQuery("SELECT (extract(epoch FROM clock_timestamp()) * 1000000)::bigint").use { rows -> rows.next(); rows.getLong(1) }
    } else Instant.now().let { it.epochSecond * 1_000_000 + it.nano / 1000 }
    fun audit(context: V1Context, action: String, record: Record) = appendAudit(context, action, record)
    internal fun prefix(kind: String): String = if (kind.startsWith("v1_")) kind else "v1_$kind"
    internal fun physicalId(kind: String, id: String): String = Secrets.hash("$tenantId:$brandId:${prefix(kind)}:$id")
}

internal fun Record.logical(): Record = copy(id = data.string("_id") ?: id)

internal fun ResultSet.v1Record(): Record = Record(
    getString("id"), getString("kind"), getString("tenant_id"), getString("location_id"), getString("owner_id"),
    json.parseToJsonElement(getString("payload")).jsonObject, getString("created_at"), getString("updated_at"), getInt("version"),
)
