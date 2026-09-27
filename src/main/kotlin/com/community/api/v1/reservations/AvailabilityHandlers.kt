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
