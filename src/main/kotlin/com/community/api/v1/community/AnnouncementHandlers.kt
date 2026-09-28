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
        .filter { revision == null || it.data.number("revision") >= revision }.associateBy { it.data.text("membershipId") }
    val allEligible = members().filter { member -> row.data.array("targetNodeIds").let { targets ->
        targets.isEmpty() || targets.any { inSubtree(member.data.text("nodeId"), it.jsonPrimitive.content) }
    } }.filter { member -> query["nodeId"]?.let { inSubtree(member.data.text("nodeId"), it) } ?: true }
    val eligible = allEligible.filter { query["pending"] != "true" || saved[it.id] == null }
    val done = allEligible.count { saved[it.id] != null }
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
        "scheduled" to timestamp(row.data.text("publishedAt")!!).isAfter(Instant.now()), "createdByName" to personName(row.ownerId))
}
private fun V1Context.checkedAnnouncement(): Record = store.get("announcement", id("announcementId"), locationId).also {
    if (!announcementVisible(it)) fail(404, "NOT_FOUND", "Comunicado não encontrado")
}
internal fun announcementHandlers(): Map<String, V1Handler> = mapOf(
    "listAnnouncements" to V1Handler { c -> c.listResponse("announcement") { row ->
        if (!c.announcementVisible(row) || (c.query["unreadOnly"] == "true" && c.receipt(row.id) != null)) JsonNull else c.announcement(row)
    } },
    "getAnnouncement" to V1Handler { c -> V1Response(c.announcement(c.checkedAnnouncement())) },
    "markAnnouncementRead" to V1Handler { c -> c.acknowledge(c.checkedAnnouncement()); V1Response(status = 204) },
    "publishAnnouncement" to V1Handler { c ->
        c.validateTargets(c.input)
        val publishedAt = c.input.text("publishAt") ?: now()
        if (c.input.text("expiresAt")?.let { !timestamp(it).isAfter(timestamp(publishedAt)) } == true)
            c.fail(422, "INVALID_TIME_RANGE", "Expiração deve ser posterior à publicação")
        val row = c.save("announcement", c.input.merge(obj("publishedAt" to publishedAt)))
        if (c.input.flag("pushNotify") && !timestamp(publishedAt).isAfter(Instant.now()))
            c.broadcast("announcement", row.id, c.input.text("title")!!, c.input.text("body"), c.input.array("targetNodeIds"))
        V1Response(c.announcement(row), 201)
    },
    "adminListAnnouncements" to V1Handler { c -> c.listResponse("announcement") { c.adminAnnouncement(it) } },
    "adminUpdateAnnouncement" to V1Handler { c ->
        c.validateTargets(c.input)
        val row = c.store.get("announcement", c.id("announcementId"), c.locationId)
        var data = row.data.merge(c.input)
        if ("publishAt" in c.input) data = data.merge(obj("publishedAt" to (c.input.text("publishAt") ?: now())))
        if (data.text("expiresAt")?.let { !timestamp(it).isAfter(timestamp(data.text("publishedAt")!!)) } == true)
            c.fail(422, "INVALID_TIME_RANGE", "Expiração deve ser posterior à publicação")
        V1Response(c.adminAnnouncement(c.change(row, data)))
    },
    "adminDeleteAnnouncement" to V1Handler { c -> c.remove(c.store.get("announcement", c.id("announcementId"), c.locationId)) },
    "adminListAnnouncementReceipts" to V1Handler { c -> c.receipts(c.store.get("announcement", c.id("announcementId"), c.locationId)) },
)
