package com.community.api.v1.community

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*

private fun V1Context.arrivalStatus(row: Record) = if (row.data.text("status") == "pending" && !timestamp(row.data.text("expiresAt")!!).isAfter(now)) "expired" else row.data.text("status")
private fun V1Context.arrival(row: Record): JsonObject {
    val nodeId = row.data.text("nodeId")!!
    val parents = mutableListOf<JsonElement>()
    var current: String? = nodeId
    val seen = mutableSetOf<String>()
    while (current != null && seen.add(current)) {
        parents.add(0, node(current))
        current = store.get("node", current, locationId).data.text("parentId")
    }
    return view("Arrival", row, obj("condominiumId" to row.locationId, "node" to node(nodeId), "nodePath" to JsonArray(parents),
        "gateName" to row.data.text("gateId")?.let { store.find("gate", it, locationId)?.data?.get("name") },
        "visitorDocumentLast4" to row.data.text("visitorDocumentLast4"), "company" to row.data["company"],
        "photoUrl" to row.data.text("photoKey")?.let { fileUrl(it) }, "status" to arrivalStatus(row),
