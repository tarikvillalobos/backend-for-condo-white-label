package com.community.api.v1.community

import com.community.api.core.json
import com.community.api.v1.*
import kotlinx.serialization.json.*

private val columns = mapOf(
    "parcels" to listOf("id", "reference", "nodeId", "membershipId", "carrier", "trackingCode", "storage", "status", "depositedAt", "deadline", "collectedAt"),
    "memberships" to listOf("id", "userId", "nodeId", "role", "status", "createdAt"),
    "visitors" to listOf("id", "name", "kind", "company", "vehiclePlate", "createdAt"),
    "access_events" to listOf("id", "gateId", "direction", "method", "subjectName", "nodeId", "vehiclePlate", "occurredAt", "recordedByName"),
    "reservations" to listOf("id", "spaceId", "nodeId", "membershipId", "status", "startsAt", "endsAt", "createdAt"),
    "tickets" to listOf("id", "reference", "kind", "category", "title", "status", "priority", "dueAt", "createdAt", "resolvedAt"),
    "pets" to listOf("id", "nodeId", "name", "species", "breed", "sex", "birthDate", "createdAt"),
    "vehicles" to listOf("id", "nodeId", "plate", "model", "color", "kind", "parkingSpot", "createdAt"),
    "audit" to listOf("id", "action", "outcome", "requestId", "createdAt", "target", "actor"),
)
internal fun V1Context.exportRows(job: com.community.api.core.Record): List<Map<String, String>> {
    val resource = job.data.text("resource")!!
    val filters = job.data["filters"] as? JsonObject ?: obj()
    fun accepts(row: JsonObject): Boolean {
        val at = row.text("occurredAt") ?: row.text("createdAt")!!
        (filters.text("nodeId")?.let { inSubtree(row.text("nodeId"), it) } ?: true) &&
            (filters.text("since")?.let { timestamp(at) >= timestamp(it) } ?: true) &&
            (filters.text("until")?.let { timestamp(at) <= timestamp(it) } ?: true) &&
            (filters.text("q")?.let { needle -> columns.getValue(resource).any { row[it]?.toString()?.contains(needle, true) == true } } ?: true)
    }.take(100001).toList()
    if (selected.size > 100000) fail(413, "EXPORT_TOO_LARGE", "Restrinja a exportação a até 100 mil linhas")
    return selected.map { row -> columns.getValue(resource).associateWith { column ->
        val value = row[column]
        when (value) { null, JsonNull -> ""; is JsonPrimitive -> value.content; else -> redact(value).toString() }
    } }
}
private fun V1Context.exportAudit(): List<JsonObject> = tx.connection.prepareStatement(
    "SELECT payload FROM audit_log WHERE tenant_id = ? AND brand_id = ? AND location_id = ? ORDER BY created_at LIMIT 100001",
).use { statement ->
    statement.setString(1, tenantId); statement.setString(2, brandId); statement.setString(3, locationId)
    statement.executeQuery().use { rows -> buildList { while (rows.next()) add(json.parseToJsonElement(rows.getString(1)).jsonObject) } }
}
