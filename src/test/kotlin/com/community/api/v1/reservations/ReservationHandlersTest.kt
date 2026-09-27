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

