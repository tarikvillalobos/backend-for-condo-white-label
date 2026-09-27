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
