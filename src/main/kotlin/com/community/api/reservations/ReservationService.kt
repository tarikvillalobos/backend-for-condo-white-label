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
