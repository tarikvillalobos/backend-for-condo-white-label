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
