package com.community.api.v1.community

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.util.UUID

internal fun V1Context.workOrder(row: Record): JsonObject {
    fun named(kind: String, field: String): JsonElement = row.data.text(field)?.let { id ->
        store.find(kind, id, locationId)?.let { obj("id" to it.id, "name" to it.data["name"]) }
    } ?: JsonNull
    return view("WorkOrder", row, obj("description" to row.data["description"], "node" to node(row.data.text("nodeId")),
        "equipment" to named("equipment", "equipmentId"), "contractor" to named("contractor", "contractorId"),
        "assigneeName" to row.data.text("assigneeUserId")?.let { personName(it) }, "ticketId" to row.data["ticketId"],
        "completedAt" to row.data["completedAt"], "cost" to row.data["cost"], "history" to JsonArray(row.data.array("history").map {
            val entry = it.jsonObject
            JsonObject(entry - "evidenceKeys").merge(obj("evidenceUrls" to JsonArray(entry.array("evidenceKeys").map { key -> JsonPrimitive(fileUrl(key.jsonPrimitive.content)) })))
        })))
}
