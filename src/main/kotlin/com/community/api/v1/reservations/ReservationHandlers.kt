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
    return V1Response(spaceView(c, saved), if (old == null) 201 else 200)
}

internal fun zone(c: V1Context): ZoneId = BookingRules.timeZone(
    c.store.get("condominium", condominium(c)).data.text("timeZone"),
)

private fun reservation(c: V1Context): Record = c.store.get("reservation", c.path.getValue("reservationId"), condominium(c)).also {
    if (c.membershipId != null && it.data.text("membershipId") != c.membershipId) c.fail(404, "NOT_FOUND", "Reservation not found")
}

private fun reservationView(c: V1Context, row: Record): JsonObject {
    val stored = row.data
    val facility = c.store.get("space", stored.text("spaceId")!!, condominium(c))
    val effectiveStatus = if (stored.text("status") == "confirmed" && !timestamp(stored.text("endsAt")).isAfter(c.now)) "completed" else stored.text("status")
    return obj("id" to row.id, "membershipId" to stored.text("membershipId"), "space" to spaceView(c, facility),
        "status" to effectiveStatus, "startsAt" to stored.text("startsAt"), "endsAt" to stored.text("endsAt"),
        "guestsCount" to stored["guestsCount"], "notes" to stored["notes"],
        "canCancel" to BookingRules.cancellable(stored, facility.data.objectAt("rules"), c.now),
        "createdAt" to row.createdAt, "cancelledAt" to stored["cancelledAt"],
        "cancellationReason" to stored["cancellationReason"], "version" to row.version)
}

private fun adminView(c: V1Context, row: Record): JsonObject {
    val member = c.store.get("membership", row.data.text("membershipId")!!, condominium(c))
    val name = member.data.text("name") ?: member.ownerId?.let { c.tx.get("account", it, c.tenantId)?.data?.text("name") }.orEmpty()
    val nodeId = member.data.text("nodeId") ?: c.fail(409, "MEMBERSHIP_NODE_REQUIRED", "Reservation holder needs a node")
    return obj("reservation" to reservationView(c, row), "holderName" to name, "node" to nodeReference(c, nodeId))
}

private fun reservations(c: V1Context): V1Response {
    val admin = c.operationId.startsWith("admin")
    val rows = c.store.list("reservation", condominium(c)).filter { row ->
        val data = row.data
        val status = data.text("status")
        val ended = !timestamp(data.text("endsAt")).isAfter(c.now)
        (admin || data.text("membershipId") == c.membershipId) &&
            (c.query["spaceId"] == null || data.text("spaceId") == c.query["spaceId"]) &&
            (if (admin) c.query["status"] == null || status == c.query["status"] || (c.query["status"] == "completed" && status == "confirmed" && ended)
             else when (c.query["status"] ?: "upcoming") { "past" -> ended && status == "confirmed"; "cancelled" -> status in setOf("cancelled", "rejected"); else -> !ended && BookingRules.active(data) }) &&
