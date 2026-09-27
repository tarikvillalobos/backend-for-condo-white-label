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
    return V1Response(pageRecords(eligible) { member -> obj("membershipId" to member.id,
        "residentName" to personName(member.data.text("userId") ?: member.ownerId), "node" to node(member.data.text("nodeId")),
        "at" to saved[member.id]?.data?.get("at")) })
}
private fun V1Context.announcementVisible(row: Record): Boolean {
    if (membershipId == null) return true
    if (!visible(row.data)) return false
    if (timestamp(row.data.text("publishedAt")!!).isAfter(Instant.now())) return false
    if (row.data.text("expiresAt")?.let { !timestamp(it).isAfter(Instant.now()) } == true) return false
    return true
}
private fun V1Context.announcement(row: Record) = view("Announcement", row, obj(
    "condominiumId" to row.locationId, "expiresAt" to row.data["expiresAt"], "readAt" to receipt(row.id)?.data?.get("at"),
    "attachments" to row.data.array("attachments"), "requiresAcknowledgment" to row.data.flag("requiresAcknowledgment"),
))
private fun V1Context.adminAnnouncement(row: Record): JsonObject {
    val targets = row.data.array("targetNodeIds")
    return obj("announcement" to announcement(row), "targetNodes" to JsonArray(targets.map { node(it.jsonPrimitive.content) }),
        "readCount" to store.list("receipt", locationId, filters = mapOf("resourceId" to row.id)).size,
        "targetCount" to members().count { member -> targets.isEmpty() || targets.any { inSubtree(member.data.text("nodeId"), it.jsonPrimitive.content) } },
