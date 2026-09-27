package com.community.api.v1.reservations

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.*

fun reservationHandlers(): Map<String, V1Handler> = mapOf(
    "listSpaces" to V1Handler(::spaces), "adminListSpaces" to V1Handler(::spaces),
    "adminCreateSpace" to V1Handler(::saveSpace), "adminUpdateSpace" to V1Handler(::saveSpace),
    "getAvailability" to V1Handler(::availability), "createReservation" to V1Handler(::createReservation),
    "listReservations" to V1Handler(::reservations), "adminListReservations" to V1Handler(::reservations),
    "getReservation" to V1Handler { c -> V1Response(reservationView(c, reservation(c))) },
    "cancelReservation" to V1Handler(::transition), "adminCancelReservation" to V1Handler(::transition),
    "adminApproveReservation" to V1Handler(::transition), "adminRejectReservation" to V1Handler(::transition),
    "adminBlockSpace" to V1Handler(::blockSpace), "adminUnblockSpace" to V1Handler(::unblockSpace),
)

internal fun condominium(c: V1Context): String = c.locationId ?: c.fail(404, "NOT_FOUND", "Condominium not found")
internal fun space(c: V1Context, id: String = c.path.getValue("spaceId")): Record =
    c.store.get("space", id, condominium(c)).also { if (!visible(c, it)) c.fail(404, "NOT_FOUND", "Space not found") }

private fun visible(c: V1Context, row: Record): Boolean {
    if (c.membership == null) return true
    if (!row.data.flag("active")) return false
    val root = row.data.text("visibleFromNodeId") ?: return true
    var id = c.membership!!.data.text("nodeId") ?: c.unitId
    val seen = mutableSetOf<String>()
    while (id != null && seen.add(id)) {
        if (id == root) return true
        id = c.store.find("node", id, condominium(c))?.data?.text("parentId")
    }
    return false
}

internal fun nodeReference(c: V1Context, id: String?): JsonElement {
    if (id == null) return JsonNull
    val node = c.store.get("node", id, condominium(c))
    return obj("id" to node.id, "type" to (node.data.text("type") ?: node.data.text("typeCode")), "label" to node.data.text("label"))
}

internal fun spaceView(c: V1Context, row: Record): JsonObject = obj(
    "id" to row.id, "node" to nodeReference(c, row.data.text("nodeId")), "name" to row.data.text("name"),
    "description" to row.data["description"], "photoUrl" to row.data["photoUrl"],
    "active" to row.data.flag("active"), "rules" to row.data.objectAt("rules"), "openingHours" to row.data.arrayAt("openingHours"),
)

private fun spaces(c: V1Context): V1Response = V1Response(c.pageItems(
    c.store.list("space", condominium(c)).filter { visible(c, it) }.map { spaceView(c, it) },
))

private fun saveSpace(c: V1Context): V1Response {
    val old = c.path["spaceId"]?.let { space(c, it) }
    old?.let(c::requireVersion)
    val data = JsonObject((old?.data ?: JsonObject(emptyMap())) + c.input)
    BookingRules.validate(data)
    listOf("nodeId", "visibleFromNodeId").forEach { key -> data.text(key)?.let { c.store.get("node", it, condominium(c)) } }
    if (data.text("photoKey") != null) c.store.get("upload", data.text("photoKey")!!, condominium(c))
    val saved = if (old == null) c.store.create("space", data, condominium(c)) else c.store.update(old, data)
    c.audit(if (old == null) "space.created" else "space.updated", saved)
