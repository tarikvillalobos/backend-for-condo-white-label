package com.community.api.v1.platform

import com.community.api.core.*
import com.community.api.v1.*
import kotlinx.serialization.json.*

internal fun JsonObject.plusFields(vararg fields: Pair<String, Any?>) = JsonObject(this + obj(*fields))
internal fun JsonObject.arr(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())
internal fun JsonObject.bool(key: String, default: Boolean = false): Boolean = get(key)?.jsonPrimitive?.booleanOrNull ?: default
internal fun V1Context.required(key: String) = input.string(key) ?: fail(422, "VALIDATION_ERROR", "$key é obrigatório")
internal fun V1Context.pathId(key: String) = path[key] ?: fail(400, "VALIDATION_ERROR", "$key é obrigatório")
internal fun V1Context.condominiumId() = locationId ?: pathId("condominiumId")
internal fun V1Context.withInput(body: JsonObject, location: String? = locationId) = V1Context(tx, operationId,
    tenantId, brandId, requestId, body, path, query, headers, principal, location, membership, now)
internal fun V1Context.platformResult(schema: String, record: Record, status: Int = 200) =
    V1Response(project(schema, record.document()), status, mapOf("ETag" to "\"${record.version}\""))
internal fun V1Context.platformUpdate(record: Record, data: JsonObject): Record {
    if (header("If-Match") != null) requireVersion(record)
    return store.update(record, data)
}
