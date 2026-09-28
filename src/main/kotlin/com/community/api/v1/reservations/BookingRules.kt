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
        }
    }

    fun timeZone(value: String?): ZoneId = try { ZoneId.of(value ?: "America/Sao_Paulo") }
        catch (_: Exception) { bookingError("VALIDATION_ERROR", "Invalid condominium time zone") }

    fun localTime(value: String?): LocalTime = try { LocalTime.parse(value) }
        catch (_: Exception) { bookingError("VALIDATION_ERROR", "Opening hours require HH:mm") }

    fun validateWindow(space: JsonObject, input: JsonObject, zone: ZoneId, now: Instant) {
        val start = timestamp(input.text("startsAt"))
        val end = timestamp(input.text("endsAt"))
        val rules = space.objectAt("rules")
        if (!space.flag("active")) bookingError("RESERVATION_CONFLICT", "Space is inactive", 409)
        if (!start.isBefore(end) || !start.isAfter(now)) bookingError("VALIDATION_ERROR", "Reservation must occupy a future interval")
        if (start.isBefore(now.plusSeconds(rules.number("minAdvanceMinutes")!!.toLong() * 60)))
            bookingError("VALIDATION_ERROR", "Reservation does not meet the minimum notice")
        if (start.atZone(zone).toLocalDate().isAfter(now.atZone(zone).toLocalDate().plusDays(rules.number("horizonDays")!!.toLong())))
            bookingError("OUTSIDE_HORIZON", "Reservation exceeds the booking horizon")
        val duration = Duration.between(start, end)
        val slot = rules.number("slotMinutes")!!.toLong()
        if (duration > Duration.ofMinutes(rules.number("maxDurationMinutes")!!.toLong()) || duration.seconds % (slot * 60) != 0L)
            bookingError("VALIDATION_ERROR", "Reservation duration must follow the space slots and limit")
        val guests = input.number("guestsCount") ?: 0
        if (guests < 0 || rules.number("capacity")?.let { guests > it } == true)
            bookingError("CAPACITY_EXCEEDED", "Guest count exceeds space capacity")
        val localStart = start.atZone(zone)
        val localEnd = end.atZone(zone)
        if (localStart.toLocalDate() != localEnd.toLocalDate()) bookingError("OUTSIDE_OPENING_HOURS", "Reservation crosses local dates")
        val opening = space.arrayAt("openingHours").map { it.jsonObject }.firstOrNull {
            it.number("weekday") == localStart.dayOfWeek.value % 7 &&
                !localStart.toLocalTime().isBefore(localTime(it.text("opens"))) &&
                !localEnd.toLocalTime().isAfter(localTime(it.text("closes")))
        } ?: bookingError("OUTSIDE_OPENING_HOURS", "Reservation is outside opening hours")
        val offset = Duration.between(localTime(opening.text("opens")), localStart.toLocalTime()).seconds
        if (start.nano != 0 || end.nano != 0 || offset % (slot * 60) != 0L) bookingError("INVALID_SLOT", "Reservation start must align with a slot")
    }

    fun overlaps(a: JsonObject, startsAt: Instant, endsAt: Instant): Boolean =
        timestamp(a.text("startsAt")).isBefore(endsAt) && timestamp(a.text("endsAt")).isAfter(startsAt)

    fun active(reservation: JsonObject): Boolean = reservation.text("status") in setOf("pending", "confirmed")

    fun cancellable(reservation: JsonObject, rules: JsonObject, now: Instant): Boolean = active(reservation) &&
        timestamp(reservation.text("startsAt")).minusSeconds((rules.number("cancelDeadlineMinutes") ?: 0).toLong() * 60).isAfter(now)
}
