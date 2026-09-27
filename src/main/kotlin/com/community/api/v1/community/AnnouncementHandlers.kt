package com.community.api.v1.community

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.Instant

internal fun V1Context.receipt(resourceId: String, revision: Int? = null): Record? = store.list("receipt", locationId,
    filters = mapOf("resourceId" to resourceId, "membershipId" to (membershipId ?: "")) +
        if (revision == null) emptyMap() else mapOf("revision" to revision.toString())).firstOrNull()
internal fun V1Context.acknowledge(row: Record, revision: Int? = null) {
    if (receipt(row.id, revision) == null) save("receipt", obj("resourceId" to row.id, "revision" to revision,
        "residentName" to personName(), "node" to node(unitId), "at" to now()))
}
internal fun V1Context.receipts(row: Record, revision: Int? = null): V1Response {
    val saved = store.list("receipt", locationId, filters = mapOf("resourceId" to row.id))
        .filter { revision == null || it.data.number("revision") == revision }.associateBy { it.data.text("membershipId") }
    val eligible = members().filter { member -> row.data.array("targetNodeIds").let { targets ->
        targets.isEmpty() || targets.any { inSubtree(member.data.text("nodeId"), it.jsonPrimitive.content) }
    } }.filter { query["pendingOnly"] != "true" || saved[it.id] == null }
