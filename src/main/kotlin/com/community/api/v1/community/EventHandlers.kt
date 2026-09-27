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
