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

    private fun database(): Database = Database.memory().also { db ->
        db.tx { tx ->
            tx.create("client", "tenant", data = body(ClientSettings("Tenant")), id = "tenant")
            tx.create("location", "tenant", data = body(Location("Property")), id = "location")
            listOf("owner", "other", "manager").forEach { userId ->
                tx.create("account", "tenant", data = buildJsonObject { put("active", true) }, id = userId)
                val role = if (userId == "manager") "property_manager" else "resident"
                tx.create("membership", "tenant", "location", userId,
                    body(Membership(userId, "location", role = role, permissions = setOf("events.manage"))))
            }
            val data = ReservationData(CreateReservation("facility", start, end), "CONFIRMED", emptyList())
            tx.create("reservation", "tenant", "location", "owner", body(data), "booking")
        }
    }

    private fun Tx.context(userId: String = "owner") = authorize(Actor(userId, "tenant", "session"), "location", "events.manage", "events")

    @Test
    fun `owner and reservation manager can link confirmed or pending bookings`() = database().use { db ->
        db.tx { tx ->
            tx.validateEventReservation(tx.context(), event)
            tx.validateEventReservation(tx.context("manager"), event)
            val row = tx.requireRecord("reservation", "booking", "tenant")
            tx.update(row, body(row.decode<ReservationData>().copy(status = "PENDING")))
            tx.validateEventReservation(tx.context(), event)
            tx.validateEventReservation(tx.context(), event.copy(reservationId = null))
        }
    }

    @Test
    fun `event management alone does not grant access to another members booking`() = database().use { db ->
        assertEquals(403, assertFailsWith<ApiException> {
            db.tx { tx -> tx.validateEventReservation(tx.context("other"), event) }
        }.status)
    }

    @Test
    fun `reservation links are confined to tenant and location`() = database().use { db ->
        db.tx { tx ->
            val data = tx.requireRecord("reservation", "booking", "tenant").data
            tx.create("reservation", "other-tenant", "location", "owner", data, "foreign-tenant")
            tx.create("reservation", "tenant", "other-location", "owner", data, "foreign-location")
        }
        listOf("foreign-tenant", "foreign-location", "missing").forEach { id ->
            assertEquals(404, assertFailsWith<ApiException> {
                db.tx { tx -> tx.validateEventReservation(tx.context("manager"), event.copy(reservationId = id)) }
            }.status)
        }
    }

    @Test
    fun `closed bookings and maintenance cannot back a community event`() = database().use { db ->
        listOf("CANCELLED", "REJECTED", "MAINTENANCE").forEach { state ->
            db.tx { tx ->
                val booking = tx.requireRecord("reservation", "booking", "tenant")
                tx.update(booking, body(booking.decode<ReservationData>().copy(status = state)))
            }
            assertEquals(409, assertFailsWith<ApiException> {
                db.tx { tx -> tx.validateEventReservation(tx.context(), event) }
            }.status)
