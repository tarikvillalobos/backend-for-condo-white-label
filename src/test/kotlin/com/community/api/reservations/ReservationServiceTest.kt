package com.community.api.reservations

import com.community.api.core.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ReservationServiceTest {
    private val service = ReservationService(Clock.fixed(Instant.parse("2030-01-01T12:00:00Z"), ZoneOffset.UTC))
    private val manager = Context(Actor("manager", "tenant", "session"), "location", setOf("*"))
    private val resident = Context(Actor("resident", "tenant", "session"), "location", setOf("reservations.create", "reservations.read.own", "facilities.read"))
    private val other = resident.copy(actor = resident.actor.copy(userId = "other"))

