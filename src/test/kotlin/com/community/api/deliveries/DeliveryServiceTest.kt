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
            db.tx { service.get(it, staff.copy(locationId = "other-location"), first.id) }
        }.status)
        assertEquals(404, assertFailsWith<ApiException> {
            db.tx { service.get(it, staff.copy(actor = staff.actor.copy(tenantId = "other-tenant")), first.id) }
        }.status)
    }

    @Test
    fun `reported pickup does not release a compartment and staff confirmation consumes credential`() = database().use { db ->
        val locker = db.tx { service.saveLocker(it, staff, LockerData("Entrance", listOf(Compartment("A", "A")))) }
        val parcel = db.tx { service.receive(it, staff, ReceivePackage("recipient", "Parcel", lockerId = locker.id, compartmentId = "A"), "receipt") }
        val credential = db.tx { service.credential(it, recipient, parcel.id, 30) }
        val stored = db.tx { it.requireRecord("package", parcel.id, "tenant").decode<PackageData>() }
        assertNotEquals(credential.credential, stored.credentialHash)
        assertFalse(body(parcel).toString().contains("credential"))
        assertEquals("PICKUP_REPORTED", db.tx { service.reportPickup(it, recipient, parcel.id) }.status)
        assertEquals(parcel.id, db.tx { service.lockers(it, staff).single().compartments.single().packageId })
        assertEquals(403, assertFailsWith<ApiException> {
            db.tx { service.confirmPickup(it, recipient, parcel.id, ConfirmPickup("recipient", credential.credential)) }
        }.status)
