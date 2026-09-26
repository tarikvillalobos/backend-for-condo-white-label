package com.community.api.community

import com.community.api.core.*
import com.community.api.platform.ClientSettings
import com.community.api.platform.Location
import com.community.api.reservations.CreateReservation
import com.community.api.reservations.ReservationData
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class EventReservationTest {
    private val start = "2030-01-02T12:00:00Z"
    private val end = "2030-01-02T14:00:00Z"
    private val event = EventInput("Community lunch", "Bring a dish", start, end, reservationId = "booking")

