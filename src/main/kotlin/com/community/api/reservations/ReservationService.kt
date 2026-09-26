package com.community.api.reservations

import com.community.api.core.*
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class ReservationService(private val clock: Clock = Clock.systemUTC()) {
    private val blocking = setOf("PENDING", "CONFIRMED", "MAINTENANCE")

    private fun Context.location(): String = locationId ?: forbidden()
    private fun Context.allow(permission: String) {
        if (!can(permission)) forbidden()
    }

    private fun instant(value: String): Instant = try { Instant.parse(value) }
        catch (_: Exception) { badRequest("Times must be ISO-8601 instants with UTC offset") }

    private fun event(ctx: Context, action: String) = ReservationEvent(action, ctx.userId, clock.instant().toString())
    private fun view(record: Record) = ReservationView(record.id, record.ownerId ?: "", record.decode())
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    fun facilities(tx: Tx, ctx: Context): List<FacilityView> {
        ctx.allow("facilities.read")
        return tx.list("facility", ctx.tenantId, ctx.location()).map { FacilityView(it.id, it.decode()) }
    }

    fun saveFacility(tx: Tx, ctx: Context, request: FacilityData, id: String? = null): FacilityView {
        ctx.allow("facilities.manage")
        validateRules(request)
        val existing = id?.let { tx.requireRecord("facility", it, ctx.tenantId, ctx.location()) }
        if (existing != null && request.maintenance && !existing.decode<FacilityData>().maintenance) {
            if (records(tx, ctx).any { it.decode<ReservationData>().let { booking ->
                    booking.request.facilityId == id && booking.status in blocking && instant(booking.request.endsAt).isAfter(clock.instant())
                } }) conflict("Cancel future reservations before placing the facility in maintenance")
        }
        val record = if (existing == null) tx.create("facility", ctx.tenantId, ctx.location(), data = body(request))
            else tx.update(existing, body(request))
        tx.audit(ctx, if (existing == null) "facility.created" else "facility.updated", record.id)
        return FacilityView(record.id, request)
    }

    private fun validateRules(rules: FacilityData) {
        if (rules.name.isBlank() || rules.name.length > 200) badRequest("Facility name must contain 1 to 200 characters")
        try { ZoneId.of(rules.timeZone) } catch (_: Exception) { badRequest("Unknown facility time zone") }
        val opens = try { LocalTime.parse(rules.opensAt) } catch (_: Exception) { badRequest("Invalid opening time") }
        val closes = try { LocalTime.parse(rules.closesAt) } catch (_: Exception) { badRequest("Invalid closing time") }
        if (!opens.isBefore(closes)) badRequest("Opening time must precede closing time on the same day")
        if (rules.weekdays.isEmpty() || rules.weekdays.any { it !in 1..7 }) badRequest("Weekdays use ISO values 1 through 7")
        if (rules.capacity !in 1..100000 || rules.maxDurationMinutes !in 1..1440 || rules.minNoticeMinutes !in 0..525600 ||
            rules.maxDaysAhead !in 1..730 || rules.maxActivePerMember !in 1..1000) badRequest("Invalid facility booking limits")
    }

    fun list(tx: Tx, ctx: Context): List<ReservationView> {
        if (!ctx.can("reservations.read.all") && !ctx.can("reservations.read.own")) forbidden()
        return records(tx, ctx).filter { ctx.can("reservations.read.all") || it.ownerId == ctx.userId }.map(::view)
    }

    fun get(tx: Tx, ctx: Context, id: String): ReservationView {
        val record = tx.requireRecord("reservation", id, ctx.tenantId, ctx.location())
        if (!ctx.can("reservations.read.all") && !(ctx.can("reservations.read.own") && record.ownerId == ctx.userId)) forbidden()
        return view(record)
    }

    fun availability(tx: Tx, ctx: Context, facilityId: String, startsAt: String, endsAt: String): FacilityAvailability {
        ctx.allow("facilities.read")
        val facility = tx.requireRecord("facility", facilityId, ctx.tenantId, ctx.location()).decode<FacilityData>()
        val start = instant(startsAt)
        val end = instant(endsAt)
        if (!start.isBefore(end) || Duration.between(start, end) > Duration.ofDays(93)) badRequest("Availability range must be positive and at most 93 days")
        val busy = records(tx, ctx).map { it.decode<ReservationData>() }.filter {
            it.request.facilityId == facilityId && it.status in blocking &&
                instant(it.request.startsAt).isBefore(end) && instant(it.request.endsAt).isAfter(start)
        }.map { AvailabilitySlot(it.request.startsAt, it.request.endsAt) }.sortedBy { it.startsAt }
        return FacilityAvailability(facilityId, facility, busy)
    }

    fun create(tx: Tx, ctx: Context, request: CreateReservation, key: String, maintenance: Boolean = false): ReservationView {
        ctx.allow(if (maintenance) "reservations.manage" else "reservations.create")
        if (key.isBlank() || key.length > 128) badRequest("Idempotency-Key must contain 1 to 128 characters")
        if (request.note.length > 2000) badRequest("Reservation note must contain at most 2000 characters")
        val operation = if (maintenance) "maintenance" else "booking"
        val keyId = digest("${ctx.tenantId}:${ctx.location()}:${ctx.userId}:$operation:$key")
        val fingerprint = digest(body(request).toString())
        tx.get("reservation_key", keyId, ctx.tenantId)?.let {
            val previous = it.decode<ReservationKey>()
            if (previous.fingerprint != fingerprint) conflict("Idempotency-Key was already used for a different reservation")
            return view(tx.requireRecord("reservation", previous.reservationId, ctx.tenantId, ctx.location()))
        }
        val facility = tx.requireRecord("facility", request.facilityId, ctx.tenantId, ctx.location()).decode<FacilityData>()
        val start = instant(request.startsAt)
        val end = instant(request.endsAt)
        if (!start.isBefore(end) || !start.isAfter(clock.instant())) badRequest("Reservation must start in the future and end after it starts")
        if (!maintenance) validateBooking(facility, request, start, end)
        val existing = records(tx, ctx)
        val active = existing.filter { it.decode<ReservationData>().let { booking ->
            booking.status in blocking && instant(booking.request.endsAt).isAfter(clock.instant())
        } }
        if (!maintenance && active.count { it.ownerId == ctx.userId && it.decode<ReservationData>().request.facilityId == request.facilityId } >= facility.maxActivePerMember) {
            conflict("Active reservation limit reached")
        }
        ensureNoOverlap(active, request.facilityId, start, end)
        val status = when { maintenance -> "MAINTENANCE"; facility.requiresApproval -> "PENDING"; else -> "CONFIRMED" }
        val record = tx.create("reservation", ctx.tenantId, ctx.location(), ctx.userId,
            body(ReservationData(request, status, listOf(event(ctx, "created")))))
        tx.create("reservation_key", ctx.tenantId, ctx.location(), ctx.userId, body(ReservationKey(fingerprint, record.id)), keyId)
        tx.audit(ctx, "reservation.created", record.id)
        if (!maintenance) tx.notify(ctx.tenantId, ctx.location(), ctx.userId, "Reservation registered", "Your reservation is ${status.lowercase()}.")
        return view(record)
    }

    private fun validateBooking(facility: FacilityData, request: CreateReservation, start: Instant, end: Instant) {
        if (facility.maintenance) conflict("Facility is under maintenance")
        if (request.attendees !in 1..facility.capacity) badRequest("Attendees exceed facility capacity")
        val now = clock.instant()
        if (start.isBefore(now.plusSeconds(facility.minNoticeMinutes * 60L))) badRequest("Reservation does not meet minimum notice")
        val zone = ZoneId.of(facility.timeZone)
        val localStart = start.atZone(zone)
        val localEnd = end.atZone(zone)
        if (localStart.toLocalDate().isAfter(now.atZone(zone).toLocalDate().plusDays(facility.maxDaysAhead.toLong()))) {
            badRequest("Reservation exceeds advance booking limit")
        }
        if (Duration.between(start, end) > Duration.ofMinutes(facility.maxDurationMinutes.toLong())) badRequest("Reservation exceeds maximum duration")
        if (localStart.toLocalDate() != localEnd.toLocalDate() || localStart.dayOfWeek.value !in facility.weekdays ||
            localStart.toLocalTime().isBefore(LocalTime.parse(facility.opensAt)) || localEnd.toLocalTime().isAfter(LocalTime.parse(facility.closesAt))) {
            badRequest("Reservation is outside the facility's local operating hours")
        }
    }

    private fun ensureNoOverlap(records: List<Record>, facilityId: String, start: Instant, end: Instant, exceptId: String? = null) {
        if (records.any { record -> record.id != exceptId && record.decode<ReservationData>().let {
                it.request.facilityId == facilityId && it.status in blocking &&
                    instant(it.request.startsAt).isBefore(end) && instant(it.request.endsAt).isAfter(start)
            } }) conflict("Facility is already reserved for this period")
    }

    fun transition(tx: Tx, ctx: Context, id: String, action: String): ReservationView {
        val record = tx.requireRecord("reservation", id, ctx.tenantId, ctx.location())
        val data = record.decode<ReservationData>()
        if (action == "cancel") {
            if (!ctx.can("reservations.manage") && !(ctx.can("reservations.create") && record.ownerId == ctx.userId)) forbidden()
        } else ctx.allow("reservations.manage")
        if (data.status !in blocking) conflict("Reservation is already closed")
        if (!instant(data.request.endsAt).isAfter(clock.instant())) conflict("Past reservations cannot be changed")
        val status = when (action) {
            "cancel" -> "CANCELLED"
            "approve" -> {
                if (data.status != "PENDING") conflict("Only pending reservations can be approved")
                val facility = tx.requireRecord("facility", data.request.facilityId, ctx.tenantId, ctx.location()).decode<FacilityData>()
                if (facility.maintenance) conflict("Facility is under maintenance")
                ensureNoOverlap(records(tx, ctx), data.request.facilityId, instant(data.request.startsAt), instant(data.request.endsAt), id)
                "CONFIRMED"
            }
            "reject" -> {
                if (data.status != "PENDING") conflict("Only pending reservations can be rejected")
                "REJECTED"
