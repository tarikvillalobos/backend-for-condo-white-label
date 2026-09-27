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
