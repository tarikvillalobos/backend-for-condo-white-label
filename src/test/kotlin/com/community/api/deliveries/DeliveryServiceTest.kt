package com.community.api.deliveries

import com.community.api.core.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class DeliveryServiceTest {
    private val clock = Clock.fixed(Instant.parse("2030-01-01T12:00:00Z"), ZoneOffset.UTC)
    private val service = DeliveryService(clock)
    private val staff = Context(Actor("staff", "tenant", "staff-session"), "standalone", setOf("*"))
    private val recipient = Context(Actor("recipient", "tenant", "resident-session"), "standalone", setOf("packages.read.own"))
    private val outsider = Context(Actor("outsider", "tenant", "other-session"), "standalone", setOf("packages.read.own"))

    private fun database(): Database = Database.memory().also { db ->
        db.tx { tx ->
            listOf("staff", "recipient", "outsider", "delegate").forEach { userId ->
                tx.create("account", "tenant", data = buildJsonObject { put("active", true) }, id = userId)
                tx.create("membership", "tenant", "standalone", userId, body(Membership(userId, "standalone")))
            }
        }
    }

    @Test
    fun `receipt is idempotent and confined to a standalone location`() = database().use { db ->
        val request = ReceivePackage("recipient", "Small parcel")
        val first = db.tx { service.receive(it, staff, request, "receipt-1") }
        val repeated = db.tx { service.receive(it, staff, request, "receipt-1") }
        assertEquals(first.id, repeated.id)
        assertEquals(1, db.tx { it.list("package", "tenant").size })
        assertEquals(1, db.tx { it.list("notification", "tenant").size })
        assertEquals(409, assertFailsWith<ApiException> {
            db.tx { service.receive(it, staff, request.copy(description = "Different"), "receipt-1") }
        }.status)
        assertEquals(403, assertFailsWith<ApiException> { db.tx { service.get(it, outsider, first.id) } }.status)
        assertEquals(404, assertFailsWith<ApiException> {
