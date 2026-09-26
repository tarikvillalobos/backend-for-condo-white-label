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

    private fun database(): Database = Database.memory().also { db ->
        db.tx { tx ->
            listOf("manager", "resident", "other").forEach { userId ->
                tx.create("account", "tenant", data = buildJsonObject { put("active", true) }, id = userId)
                tx.create("membership", "tenant", "location", userId, body(Membership(userId, "location")))
            }
        }
    }

    private fun facility(db: Database, approval: Boolean = false): String = db.tx {
        service.saveFacility(it, manager, FacilityData("Pool", capacity = 10, requiresApproval = approval)).id
    }

    private fun request(facility: String) = CreateReservation(facility, "2030-01-02T12:00:00Z", "2030-01-02T13:00:00Z", 3)

    @Test
    fun `reservation retries are idempotent and conflicting payloads are rejected`() = database().use { db ->
        val request = request(facility(db))
        val booking = db.tx { service.create(it, resident, request, "booking") }
        assertEquals(booking.id, db.tx { service.create(it, resident, request, "booking") }.id)
        assertEquals(1, db.tx { it.list("reservation", "tenant").size })
        assertEquals(409, assertFailsWith<ApiException> {
            db.tx { service.create(it, resident, request.copy(attendees = 4), "booking") }
        }.status)
        assertEquals(403, assertFailsWith<ApiException> { db.tx { service.get(it, other, booking.id) } }.status)
        assertTrue(db.tx { service.list(it, other) }.isEmpty())
        assertEquals(404, assertFailsWith<ApiException> {
            db.tx { service.get(it, manager.copy(locationId = "elsewhere"), booking.id) }
        }.status)
        assertEquals(403, assertFailsWith<ApiException> {
            db.tx { service.transition(it, other, booking.id, "cancel") }
        }.status)
    }

    @Test
    fun `simultaneous overlapping requests result in one reservation`() = database().use { db ->
        val request = request(facility(db))
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(resident, other).mapIndexed { index, actor -> executor.submit<Int> {
                gate.await()
                try { db.tx { service.create(it, actor, request, "parallel-$index") }; 201 }
                catch (failure: ApiException) { failure.status }
            } }
            gate.countDown()
            assertEquals(listOf(201, 409), futures.map { it.get(10, TimeUnit.SECONDS) }.sorted())
            assertEquals(1, db.tx { it.list("reservation", "tenant").size })
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `facility local hours capacity and adjacent reservations are respected`() = database().use { db ->
        val facility = facility(db)
        val request = request(facility)
        assertEquals(400, assertFailsWith<ApiException> {
            db.tx { service.create(it, resident, request.copy(startsAt = "2030-01-02T09:00:00Z", endsAt = "2030-01-02T10:00:00Z"), "early") }
        }.status)
        assertEquals(400, assertFailsWith<ApiException> {
            db.tx { service.create(it, resident, request.copy(attendees = 11), "crowded") }
        }.status)
        db.tx { service.create(it, resident, request, "first") }
        db.tx { service.create(it, other, request.copy(startsAt = request.endsAt, endsAt = "2030-01-02T14:00:00Z"), "adjacent") }
        val availability = db.tx { service.availability(it, resident, facility, "2030-01-02T00:00:00Z", "2030-01-03T00:00:00Z") }
        assertEquals(2, availability.busy.size)
        assertFalse(body(availability).toString().contains("ownerId"))
    }

    @Test
    fun `approval and cancellation preserve history and release the reserved period`() = database().use { db ->
        val request = request(facility(db, approval = true))
        val booking = db.tx { service.create(it, resident, request, "first") }
        assertEquals("PENDING", booking.details.status)
        assertEquals(403, assertFailsWith<ApiException> { db.tx { service.transition(it, resident, booking.id, "approve") } }.status)
        assertEquals(409, assertFailsWith<ApiException> { db.tx { service.create(it, other, request, "overlap") } }.status)
        assertEquals("CONFIRMED", db.tx { service.transition(it, manager, booking.id, "approve") }.details.status)
        val cancelled = db.tx { service.transition(it, resident, booking.id, "cancel") }
        assertEquals(listOf("created", "approve", "cancel"), cancelled.details.history.map { it.action })
        assertEquals("PENDING", db.tx { service.create(it, other, request, "after-cancel") }.details.status)
    }
