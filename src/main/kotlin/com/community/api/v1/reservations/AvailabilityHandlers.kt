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
