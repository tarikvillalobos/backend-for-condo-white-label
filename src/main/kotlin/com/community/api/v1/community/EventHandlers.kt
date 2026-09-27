package com.community.api.v1.community

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.Instant

internal fun V1Context.event(row: Record): JsonObject {
    val attendance = store.list("attendance", locationId, filters = mapOf("eventId" to row.id))
    return view("CondoEvent", row, obj("description" to row.data["description"], "location" to row.data["location"],
        "capacity" to row.data["capacity"], "rsvpEnabled" to row.data.flag("rsvpEnabled"), "attendeesCount" to attendance.size,
        "attending" to attendance.any { it.data.text("membershipId") == membershipId }, "cancelledAt" to row.data["cancelledAt"]))
}
private fun V1Context.changeAttendance(attend: Boolean): V1Response {
    val row = store.get("event", id("eventId"), locationId)
    if (row.data.text("cancelledAt") != null || !timestamp(row.data.text("endsAt")!!).isAfter(Instant.now()))
        fail(409, "EVENT_UNAVAILABLE", "Evento cancelado ou encerrado")
    if (!row.data.flag("rsvpEnabled")) fail(409, "EVENT_RSVP_DISABLED", "Este evento não recebe confirmações")
    val entries = store.list("attendance", locationId, filters = mapOf("eventId" to row.id))
    val existing = entries.firstOrNull { it.data.text("membershipId") == membershipId }
    if (attend && existing == null) {
        val capacity = row.data.text("capacity")?.toIntOrNull()
        if (capacity != null && entries.size >= capacity) fail(409, "EVENT_FULL", "Capacidade do evento atingida")
        save("attendance", obj("eventId" to row.id, "residentName" to personName(), "node" to node(unitId), "confirmedAt" to now()))
    }
    if (!attend && existing != null) { store.delete(existing); audit("event.attendance_cancelled", existing) }
    return V1Response(event(row))
}
internal fun eventHandlers(): Map<String, V1Handler> = mapOf(
    "listEvents" to V1Handler { c -> c.listResponse("event") { c.event(it) } },
    "adminCreateEvent" to V1Handler { c ->
        window(c.input)
        val row = c.save("event", c.input.merge(obj("rsvpEnabled" to c.input.flag("rsvpEnabled"), "cancelledAt" to null)))
        if (c.input.flag("notify")) c.broadcast("system", row.id, c.input.text("title")!!, c.input.text("description"))
        V1Response(c.event(row), 201)
    },
    "adminUpdateEvent" to V1Handler { c ->
        val row = c.store.get("event", c.id("eventId"), c.locationId)
        val data = row.data.merge(c.input)
        window(data)
