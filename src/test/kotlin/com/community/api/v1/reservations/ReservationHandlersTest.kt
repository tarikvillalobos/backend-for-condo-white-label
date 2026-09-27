package com.community.api.v1.reservations

import com.community.api.core.*
import com.community.api.v1.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ReservationHandlersTest {
    private val now = Instant.parse("2030-01-01T12:00:00Z")
    private val handlers = reservationHandlers()
    private val rules = obj("slotMinutes" to 30, "maxDurationMinutes" to 120, "maxFutureReservations" to 5,
        "horizonDays" to 30, "minAdvanceMinutes" to 60, "cancelDeadlineMinutes" to 120,
        "requiresApproval" to true, "fee" to null, "capacity" to 20)
    private val opening = (0..6).map { obj("weekday" to it, "opens" to "08:00", "closes" to "20:00") }
