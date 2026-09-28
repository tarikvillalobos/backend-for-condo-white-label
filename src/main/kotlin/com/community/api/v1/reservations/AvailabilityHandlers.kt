package com.community.api.v1.reservations

import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.*

internal fun availability(c: V1Context): V1Response {
    val facility = space(c)
    val date = try { LocalDate.parse(c.query["date"]) }
        catch (_: Exception) { c.fail(422, "VALIDATION_ERROR", "A valid date is required") }
    val zone = zone(c)
    val rules = facility.data.objectAt("rules")
    val minutes = rules.number("slotMinutes")!!.toLong()
    val slots = facility.data.arrayAt("openingHours").map { it.jsonObject }
        .filter { it.number("weekday") == date.dayOfWeek.value % 7 }.sortedBy { it.text("opens") }.flatMap { opening ->
            val close = date.atTime(BookingRules.localTime(opening.text("closes"))).atZone(zone).toInstant()
            var current = date.atTime(BookingRules.localTime(opening.text("opens"))).atZone(zone).toInstant()
            buildList {
                while (!current.plusSeconds(minutes * 60).isAfter(close)) {
                    val end = current.plusSeconds(minutes * 60)
                    val reason = when {
                        !facility.data.flag("active") -> "closed"
                        current.isBefore(c.now.plusSeconds(rules.number("minAdvanceMinutes")!!.toLong() * 60)) -> "past"
                        date.isAfter(c.now.atZone(zone).toLocalDate().plusDays(rules.number("horizonDays")!!.toLong())) -> "closed"
                        blocked(c, facility.id, current, end) -> "blocked"
                        occupied(c, facility.id, current, end) -> "reserved"
                        else -> null
                    }
                    add(obj("startsAt" to current.toString(), "endsAt" to end.toString(), "available" to (reason == null), "reason" to reason))
                    current = end
                }
            }
        }
    return V1Response(obj("spaceId" to facility.id, "date" to date.toString(), "timeZone" to zone.id,
        "slots" to JsonArray(slots), "generatedAt" to c.now.toString()))
}

internal fun blockSpace(c: V1Context): V1Response {
    val facility = space(c)
    val start = timestamp(c.input.text("startsAt"))
    val end = timestamp(c.input.text("endsAt"))
    if (!start.isBefore(end)) c.fail(422, "VALIDATION_ERROR", "Block must have a positive duration")
    val conflicts = c.store.list("reservation", condominium(c)).filter {
        it.data.text("spaceId") == facility.id && BookingRules.active(it.data) && BookingRules.overlaps(it.data, start, end)
    }
    if (conflicts.isNotEmpty() && !c.input.flag("cancelConflicting"))
        c.fail(409, "RESERVATION_CONFLICT", "Block overlaps existing reservations")
    conflicts.forEach { previous ->
        val updated = c.store.update(previous, previous.data.changed("status" to JsonPrimitive("cancelled"),
            "cancelledAt" to JsonPrimitive(c.now.toString()), "cancellationReason" to (c.input["reason"] ?: JsonNull)))
        c.audit("reservation.cancelled_by_block", updated)
        notifyReservation(c, updated)
    }
    val row = c.store.create("space_block", c.input.changed("spaceId" to JsonPrimitive(facility.id)), condominium(c), c.userId)
    c.audit("space.blocked", row)
    return V1Response(obj("id" to row.id, "startsAt" to start.toString(), "endsAt" to end.toString(), "reason" to c.input["reason"]), 201)
}

internal fun unblockSpace(c: V1Context): V1Response {
    val facility = space(c)
    val block = c.store.get("space_block", c.path.getValue("blockId"), condominium(c))
    if (block.data.text("spaceId") != facility.id) c.fail(404, "RESOURCE_NOT_FOUND", "Space block not found")
    c.store.delete(block)
    c.audit("space.unblocked", block)
    return V1Response(status = 204)
}
