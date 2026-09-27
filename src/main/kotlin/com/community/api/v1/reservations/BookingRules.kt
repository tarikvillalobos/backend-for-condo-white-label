package com.community.api.v1.reservations

import com.community.api.core.ApiException
import kotlinx.serialization.json.*
import java.time.*

internal fun bookingError(code: String, detail: String, status: Int = 422): Nothing =
    throw ApiException(status, code, detail)
internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.number(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
internal fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true
internal fun JsonObject.objectAt(key: String): JsonObject = get(key) as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.arrayAt(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())
internal fun JsonObject.changed(vararg values: Pair<String, JsonElement>): JsonObject = JsonObject(this + values)
internal fun timestamp(value: String?): Instant = try { Instant.parse(value) }
    catch (_: Exception) { bookingError("VALIDATION_ERROR", "A valid UTC timestamp is required") }
internal fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull

internal object BookingRules {
    fun validate(space: JsonObject) {
        if (space.text("name").isNullOrBlank()) bookingError("VALIDATION_ERROR", "Space name is required")
        val rules = space.objectAt("rules")
        val positive = listOf("slotMinutes", "maxDurationMinutes", "horizonDays")
        val nonNegative = listOf("maxFutureReservations", "minAdvanceMinutes", "cancelDeadlineMinutes")
        if (positive.any { (rules.number(it) ?: 0) <= 0 } || nonNegative.any { (rules.number(it) ?: -1) < 0 })
            bookingError("VALIDATION_ERROR", "Invalid reservation limits")
        if (rules.number("slotMinutes")!! < 15 || rules.number("maxDurationMinutes")!! < rules.number("slotMinutes")!!)
            bookingError("VALIDATION_ERROR", "Duration must accommodate at least one slot")
        if (rules.number("capacity")?.let { it < 1 } == true)
            bookingError("VALIDATION_ERROR", "Capacity must be positive")
        val hours = space.arrayAt("openingHours").map { it.jsonObject }
        hours.forEach { opening ->
            if (opening.number("weekday") !in 0..6 || !localTime(opening.text("opens")).isBefore(localTime(opening.text("closes"))))
                bookingError("VALIDATION_ERROR", "Opening hours must be ordered within the day")
        }
        hours.groupBy { it.number("weekday") }.values.forEach { day ->
            day.sortedBy { it.text("opens") }.zipWithNext().forEach { (a, b) ->
                if (localTime(a.text("closes")).isAfter(localTime(b.text("opens"))))
                    bookingError("VALIDATION_ERROR", "Opening intervals cannot overlap")
            }
