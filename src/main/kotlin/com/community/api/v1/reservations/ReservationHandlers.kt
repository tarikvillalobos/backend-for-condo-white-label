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
).mapValues { (_, handler) -> V1Handler { c ->
    c.tx.lock("reservations:${c.tenantId}:${c.brandId}:${condominium(c)}")
    handler.handle(c)
} }

internal fun condominium(c: V1Context): String = c.locationId ?: c.fail(404, "RESOURCE_NOT_FOUND", "Condominium not found")
internal fun space(c: V1Context, id: String = c.path.getValue("spaceId")): Record =
    c.store.get("space", id, condominium(c)).also { if (!visible(c, it)) c.fail(404, "RESOURCE_NOT_FOUND", "Space not found") }

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
    "description" to row.data["description"], "photoUrl" to row.data.text("photoKey")?.let(c::fileUrl),
    "active" to row.data.flag("active"), "rules" to row.data.objectAt("rules"), "openingHours" to row.data.arrayAt("openingHours"),
)

private fun spaces(c: V1Context): V1Response = V1Response(c.page("space", condominium(c), predicate = { visible(c, it) }) { spaceView(c, it) })

private fun saveSpace(c: V1Context): V1Response {
    val old = c.path["spaceId"]?.let { space(c, it) }
    if (c.header("If-Match") != null) old?.let(c::requireVersion)
    val data = JsonObject((old?.data ?: JsonObject(emptyMap())) + c.input)
    BookingRules.validate(data)
    listOf("nodeId", "visibleFromNodeId").forEach { key -> data.text(key)?.let { c.store.get("node", it, condominium(c)) } }
    c.input.text("photoKey")?.let { key ->
        val upload = c.store.get("upload", key)
        if (upload.ownerId != c.userId) c.fail(404, "RESOURCE_NOT_FOUND", "Upload not found")
        if (upload.data.text("status") != "complete") c.fail(409, "OPERATION_IN_PROGRESS", "Complete the upload first")
    }
    val saved = if (old == null) c.store.create("space", data, condominium(c)) else c.store.update(old, data)
    c.audit(if (old == null) "space.created" else "space.updated", saved)
    return V1Response(spaceView(c, saved), if (old == null) 201 else 200)
}

internal fun zone(c: V1Context): ZoneId = BookingRules.timeZone(
    c.store.get("condominium", condominium(c)).data.text("timeZone"),
)

private fun reservation(c: V1Context): Record = c.store.get("reservation", c.path.getValue("reservationId"), condominium(c)).also {
    if (c.membershipId != null && it.data.text("membershipId") != c.membershipId) c.fail(404, "RESOURCE_NOT_FOUND", "Reservation not found")
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
    val nodeId = member.data.text("nodeId") ?: c.fail(409, "NODE_PATH_NOT_FOUND", "Reservation holder needs a node")
    return obj("reservation" to reservationView(c, row), "holderName" to name, "node" to nodeReference(c, nodeId))
}

private fun reservations(c: V1Context): V1Response {
    val admin = c.operationId.startsWith("admin")
    val matches: (Record) -> Boolean = { row ->
        val data = row.data
        val ended = !timestamp(data.text("endsAt")).isAfter(c.now)
        val status = if (data.text("status") == "confirmed" && ended) "completed" else data.text("status")
        (admin || data.text("membershipId") == c.membershipId) &&
            (c.query["spaceId"] == null || data.text("spaceId") == c.query["spaceId"]) &&
            (c.query["nodeId"] == null || holderInNode(c, data.text("membershipId")!!, c.query.getValue("nodeId"))) &&
            (if (admin) c.query["status"] == null || status == c.query["status"]
             else when (c.query["status"] ?: "upcoming") { "past" -> ended && status == "completed"; "cancelled" -> status in setOf("cancelled", "rejected"); else -> !ended && BookingRules.active(data) }) &&
            (c.query["since"] == null || !timestamp(data.text("startsAt")).isBefore(timestamp(c.query["since"]))) &&
            (c.query["until"] == null || timestamp(data.text("startsAt")).isBefore(timestamp(c.query["until"])))
    }
    return V1Response(c.page("reservation", condominium(c), sortField = "startsAt", predicate = matches) { if (admin) adminView(c, it) else reservationView(c, it) })
}

internal fun occupied(c: V1Context, spaceId: String, start: Instant, end: Instant, except: String? = null): Boolean =
    c.store.list("reservation", condominium(c), filters = mapOf("spaceId" to spaceId)).any {
        it.id != except && BookingRules.active(it.data) && BookingRules.overlaps(it.data, start, end)
    }

internal fun blocked(c: V1Context, spaceId: String, start: Instant, end: Instant): Boolean =
    c.store.list("space_block", condominium(c), filters = mapOf("spaceId" to spaceId)).any { BookingRules.overlaps(it.data, start, end) }

private fun createReservation(c: V1Context): V1Response {
    val facility = space(c, c.input.text("spaceId")!!)
    BookingRules.validateWindow(facility.data, c.input, zone(c), c.now)
    val start = timestamp(c.input.text("startsAt"))
    val end = timestamp(c.input.text("endsAt"))
    val rules = facility.data.objectAt("rules")
    val count = c.store.list("reservation", condominium(c), filters = mapOf("membershipId" to c.membershipId!!,
        "spaceId" to facility.id)).count {
        BookingRules.active(it.data) && timestamp(it.data.text("endsAt")).isAfter(c.now)
    }
    if (count >= rules.number("maxFutureReservations")!!) c.fail(409, "RESERVATION_LIMIT", "Future reservation limit reached")
    if (occupied(c, facility.id, start, end) || blocked(c, facility.id, start, end))
        c.fail(409, "RESERVATION_CONFLICT", "The requested period is unavailable")
    val row = c.store.create("reservation", c.input.changed(
        "membershipId" to JsonPrimitive(c.membershipId!!), "status" to JsonPrimitive(if (rules.flag("requiresApproval")) "pending" else "confirmed"),
        "cancelledAt" to JsonNull, "cancellationReason" to JsonNull,
    ), condominium(c), c.userId)
    c.audit("reservation.created", row)
    if (row.data.text("status") == "confirmed") c.audit("reservation.confirmed", row)
    notifyReservation(c, row)
    return V1Response(reservationView(c, row), 201)
}

private fun transition(c: V1Context): V1Response {
    val row = reservation(c)
    if (c.header("If-Match") != null) c.requireVersion(row)
    val facility = c.store.get("space", row.data.text("spaceId")!!, condominium(c))
    val action = when (c.operationId) { "adminApproveReservation" -> "confirmed"; "adminRejectReservation" -> "rejected"; else -> "cancelled" }
    if (action == "cancelled" && row.data.text("status") == "cancelled")
        return V1Response(if (c.operationId.startsWith("admin")) adminView(c, row) else reservationView(c, row))
    if (!BookingRules.active(row.data) || !timestamp(row.data.text("endsAt")).isAfter(c.now))
        c.fail(409, "RESERVATION_CONFLICT", "Reservation cannot be changed in this state")
    if (c.operationId == "cancelReservation" && !BookingRules.cancellable(row.data, facility.data.objectAt("rules"), c.now))
        c.fail(422, "VALIDATION_ERROR", "Cancellation deadline has passed")
    if (action in setOf("confirmed", "rejected") && row.data.text("status") != "pending")
        c.fail(409, "RESERVATION_CONFLICT", "Only pending reservations can be approved or rejected")
    if (action == "confirmed" && (!facility.data.flag("active") || occupied(c, facility.id, timestamp(row.data.text("startsAt")), timestamp(row.data.text("endsAt")), row.id) ||
            blocked(c, facility.id, timestamp(row.data.text("startsAt")), timestamp(row.data.text("endsAt")))))
        c.fail(409, "RESERVATION_CONFLICT", "The requested period is unavailable")
    val updated = c.store.update(row, row.data.changed("status" to JsonPrimitive(action),
        "cancelledAt" to (if (action == "cancelled") JsonPrimitive(c.now.toString()) else JsonNull), "cancellationReason" to (c.input["reason"] ?: JsonNull)))
    c.audit("reservation.$action", updated)
    notifyReservation(c, updated)
    return V1Response(if (c.operationId.startsWith("admin")) adminView(c, updated) else reservationView(c, updated))
}

internal fun notifyReservation(c: V1Context, row: Record) {
    val profile = row.ownerId?.let { c.store.find("profile", it) }
    if ((profile?.data?.get("preferences") as? JsonObject)?.get("inApp") == JsonPrimitive(false)) return
    c.store.create("notification", obj("membershipId" to row.data.text("membershipId"), "title" to "Reserva atualizada",
        "body" to "Status da reserva: ${row.data.text("status")}", "kind" to "reservation", "referenceId" to row.id, "readAt" to null), condominium(c), row.ownerId)
}

private fun holderInNode(c: V1Context, membershipId: String, rootId: String): Boolean {
    var current = c.store.get("membership", membershipId, condominium(c)).data.text("nodeId")
    val seen = mutableSetOf<String>()
    while (current != null && seen.add(current)) {
        if (current == rootId) return true
        current = c.store.get("node", current, condominium(c)).data.text("parentId")
    }
    return false
}
