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

    private fun database(): Database = Database.memory().also { db -> db.tx { tx ->
        val store = V1Store(tx, "tenant", "brand")
        store.create("condominium", obj("name" to "Condo", "timeZone" to "America/Sao_Paulo", "address" to "Rua 1"), id = "condo")
        store.create("node", obj("type" to "unit", "label" to "101"), "condo", id = "unit")
        listOf("alice", "bob").forEach { user ->
            tx.create("account", "tenant", data = obj("name" to user, "email" to "$user@example.test", "active" to true), id = user)
            store.create("membership", obj("userId" to user, "nodeId" to "unit", "status" to "active"), "condo", user, user)
        }
    } }

    private fun call(db: Database, operation: String, input: JsonObject = obj(), path: Map<String, String> = emptyMap(),
        user: String = "alice", staff: Boolean = false, at: Instant = now, query: Map<String, String> = emptyMap()): V1Response = db.scopedTx(null) { tx ->
        val store = V1Store(tx, "tenant", "brand")
        val context = V1Context(tx, operation, "tenant", "brand", UUID.randomUUID().toString(), input,
            path = path, query = query, principal = V1Principal(Actor(user, "tenant", "session"), staff = staff, permissions = setOf("*")),
            locationId = "condo", membership = if (staff) null else store.get("membership", user), now = at)
        handlers.getValue(operation).handle(context).also { validateResponse(operation, it) }
    }

    private fun validateResponse(operation: String, response: V1Response) {
        val definition = Contract.operations.find { it.id == operation }?.definition ?: return
        val schema = definition["responses"]?.jsonObject?.get(response.status.toString())?.jsonObject
            ?.get("content")?.jsonObject?.get("application/json")?.jsonObject?.get("schema")?.jsonObject ?: return
        Contract.validate(schema, response.body)
    }
    private fun space(db: Database): String = call(db, "adminCreateSpace", obj("name" to "Pool", "active" to true,
        "rules" to rules, "openingHours" to opening), staff = true).body.jsonObject.string("id")!!
    private fun input(spaceId: String) = obj("spaceId" to spaceId, "startsAt" to "2030-01-02T12:00:00Z", "endsAt" to "2030-01-02T13:00:00Z", "guestsCount" to 3)

    @Test fun `concurrent reservations for same slot produce one booking and one conflict`() = database().use { db ->
        val input = input(space(db))
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf("alice", "bob").map { user -> pool.submit<Int> {
                gate.await()
                try { call(db, "createReservation", input, user = user).status } catch (failure: ApiException) { failure.status }
            } }
            gate.countDown()
            assertEquals(listOf(201, 409), futures.map { it.get(15, TimeUnit.SECONDS) }.sorted())
            assertEquals(1, db.tx { V1Store(it, "tenant", "brand").list("reservation").size })
        } finally { pool.shutdownNow() }
    }

    @Test fun `pending reservation blocks slots then approval and cancellation preserve state`() = database().use { db ->
        val space = space(db)
        val created = call(db, "createReservation", input(space)).body.jsonObject
        assertEquals("pending", created.string("status"))
        val reservationId = created.string("id")!!
        val path = mapOf("reservationId" to reservationId)
        val approved = call(db, "adminApproveReservation", path = path, staff = true).body.jsonObject
        assertEquals("confirmed", approved["reservation"]!!.jsonObject.string("status"))
        val slots = call(db, "getAvailability", path = mapOf("spaceId" to space), query = mapOf("date" to "2030-01-02")).body.jsonObject["slots"]!!.jsonArray
        assertEquals(2, slots.count { it.jsonObject.string("reason") == "reserved" })
        assertEquals(404, assertFailsWith<ApiException> { call(db, "getReservation", path = path, user = "bob") }.status)
        val cancelled = call(db, "cancelReservation", path = path).body.jsonObject
        assertEquals("cancelled", cancelled.string("status"))
        assertEquals("cancelled", call(db, "cancelReservation", path = path).body.jsonObject.string("status"))
        assertEquals(201, call(db, "createReservation", input(space), user = "bob").status)
    }

    @Test fun `blocks cancel conflicting bookings only when explicitly requested`() = database().use { db ->
        val space = space(db)
        val created = call(db, "createReservation", input(space)).body.jsonObject
        val block = obj("startsAt" to "2030-01-02T12:00:00Z", "endsAt" to "2030-01-02T13:00:00Z", "cancelConflicting" to false, "reason" to "Maintenance")
        assertEquals(409, assertFailsWith<ApiException> { call(db, "adminBlockSpace", block, mapOf("spaceId" to space), staff = true) }.status)
        val blocked = call(db, "adminBlockSpace", JsonObject(block + obj("cancelConflicting" to true)), mapOf("spaceId" to space), staff = true).body.jsonObject
        val current = call(db, "getReservation", path = mapOf("reservationId" to created.string("id")!!)).body.jsonObject
        assertEquals("cancelled", current.string("status"))
        assertEquals("Maintenance", current.string("cancellationReason"))
        assertEquals(409, assertFailsWith<ApiException> { call(db, "createReservation", input(space)) }.status)
        call(db, "adminUnblockSpace", path = mapOf("spaceId" to space, "blockId" to blocked.string("id")!!), staff = true)
        assertEquals(201, call(db, "createReservation", input(space)).status)
    }

    @Test fun `opening hours slot alignment capacity and cancellation deadline are enforced`() = database().use { db ->
        val space = space(db)
        for (invalid in listOf(
            JsonObject(input(space) + obj("startsAt" to "2030-01-02T12:10:00Z", "endsAt" to "2030-01-02T13:10:00Z")),
            JsonObject(input(space) + obj("startsAt" to "2030-01-02T09:00:00Z", "endsAt" to "2030-01-02T10:00:00Z")),
            JsonObject(input(space) + obj("guestsCount" to 21)),
        )) assertEquals(422, assertFailsWith<ApiException> { call(db, "createReservation", invalid) }.status)
        val created = call(db, "createReservation", input(space)).body.jsonObject
        assertEquals(422, assertFailsWith<ApiException> { call(db, "cancelReservation", path = mapOf("reservationId" to created.string("id")!!), at = Instant.parse("2030-01-02T11:00:00Z")) }.status)
    }
}
