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
        }
    }

    @Test
    fun `event must fit inside its reservation and active links are exclusive`() = database().use { db ->
        for (invalid in listOf(event.copy(startsAt = "2030-01-02T11:59:00Z"), event.copy(endsAt = "2030-01-02T14:01:00Z"))) {
            assertEquals(400, assertFailsWith<ApiException> {
                db.tx { tx -> tx.validateEventReservation(tx.context(), invalid) }
            }.status)
        }
        val saved = db.tx { tx ->
            val ctx = tx.context()
            tx.validateEventReservation(ctx, event)
            tx.saved(ctx, "event", body(CommunityEvent(event)))
        }
        db.tx { tx -> tx.validateEventReservation(tx.context(), event, saved.id) }
        assertEquals(409, assertFailsWith<ApiException> {
            db.tx { tx -> tx.validateEventReservation(tx.context(), event) }
        }.status)
        db.tx { tx ->
            tx.update(tx.requireRecord("event", saved.id, "tenant"), body(CommunityEvent(event, cancelled = true)))
            tx.validateEventReservation(tx.context(), event)
        }
    }

    @Test
    fun `linking checks the currently enabled reservation feature`() = database().use { db ->
        val ctx = db.tx { it.context() }
        db.tx { tx ->
            val location = tx.requireRecord("location", "location", "tenant")
            tx.update(location, body(location.decode<Location>().copy(features = allFeatures - "reservations")))
        }
        assertEquals(403, assertFailsWith<ApiException> { db.tx { it.validateEventReservation(ctx, event) } }.status)
        db.tx { it.validateEventReservation(ctx, event.copy(reservationId = null)) }
    }

    @Test
    fun `concurrent event creation cannot share one active reservation`() = database().use { db ->
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { executor.submit<Int> {
                gate.await()
                try {
                    db.tx { tx ->
                        val ctx = tx.context()
                        tx.validateEventReservation(ctx, event)
                        tx.saved(ctx, "event", body(CommunityEvent(event)))
                    }
                    201
                } catch (failure: ApiException) { failure.status }
            } }
            gate.countDown()
            assertEquals(listOf(201, 409), results.map { it.get(10, TimeUnit.SECONDS) }.sorted())
        } finally { executor.shutdownNow() }
    }
}
