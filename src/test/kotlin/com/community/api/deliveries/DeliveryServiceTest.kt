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
        assertEquals("COLLECTED", db.tx { service.confirmPickup(it, staff, parcel.id, ConfirmPickup("recipient", credential.credential)) }.status)
        assertNull(db.tx { service.lockers(it, staff).single().compartments.single().packageId })
        assertEquals(409, assertFailsWith<ApiException> {
            db.tx { service.confirmPickup(it, staff, parcel.id, ConfirmPickup("recipient", credential.credential)) }
        }.status)
    }

    @Test
    fun `delegation is explicit and changing delegates revokes existing credentials`() = database().use { db ->
        val parcel = db.tx { service.receive(it, staff, ReceivePackage("recipient", "Parcel"), "receipt") }
        val first = db.tx { service.credential(it, recipient, parcel.id, 30) }
        db.tx { service.delegate(it, recipient, parcel.id, "delegate") }
        assertEquals(403, assertFailsWith<ApiException> {
            db.tx { service.confirmPickup(it, staff, parcel.id, ConfirmPickup("delegate", first.credential)) }
        }.status)
        val credential = db.tx { service.credential(it, recipient, parcel.id, 30) }
        assertEquals(403, assertFailsWith<ApiException> {
            db.tx { service.confirmPickup(it, staff, parcel.id, ConfirmPickup("outsider", credential.credential)) }
        }.status)
        val delegate = outsider.copy(actor = outsider.actor.copy(userId = "delegate"))
        assertEquals(parcel.id, db.tx { service.get(it, delegate, parcel.id) }.id)
        assertEquals("delegate", db.tx { service.confirmPickup(it, staff, parcel.id, ConfirmPickup("delegate", credential.credential)) }.collectorId)
    }

    @Test
    fun `expired and revoked credentials cannot confirm collection`() = database().use { db ->
        val parcel = db.tx { service.receive(it, staff, ReceivePackage("recipient", "Parcel"), "receipt") }
        val credential = db.tx { service.credential(it, recipient, parcel.id, 1) }
        val later = DeliveryService(Clock.offset(clock, java.time.Duration.ofMinutes(2)))
        assertEquals(403, assertFailsWith<ApiException> {
            db.tx { later.confirmPickup(it, staff, parcel.id, ConfirmPickup("recipient", credential.credential)) }
        }.status)
        db.tx { service.revokeCredential(it, recipient, parcel.id) }
        assertEquals(403, assertFailsWith<ApiException> {
            db.tx { service.confirmPickup(it, staff, parcel.id, ConfirmPickup("recipient", credential.credential)) }
        }.status)
    }

    @Test
    fun `occupied and maintenance compartments cannot receive another package`() = database().use { db ->
        val locker = db.tx { service.saveLocker(it, staff, LockerData("Reception", listOf(Compartment("A", "A"), Compartment("B", "B", true)))) }
        val request = ReceivePackage("recipient", "Parcel", lockerId = locker.id, compartmentId = "A")
        db.tx { service.receive(it, staff, request, "one") }
        assertEquals(409, assertFailsWith<ApiException> { db.tx { service.receive(it, staff, request, "two") } }.status)
        assertEquals(409, assertFailsWith<ApiException> { db.tx { service.receive(it, staff, request.copy(compartmentId = "B"), "three") } }.status)
        assertEquals(1, db.tx { it.list("package", "tenant").size })
    }

    @Test
    fun `trusted events are scoped deduplicated and ignore stale transitions`() = database().use { db ->
        db.tx { it.create("integration", "tenant", "standalone", data = buildJsonObject { put("type", "locker"); put("active", true) }, id = "hardware") }
        val locker = db.tx { service.saveLocker(it, staff, LockerData("Reception", listOf(Compartment("A", "A")), integrationId = "hardware")) }
        val parcel = db.tx { service.receive(it, staff, ReceivePackage("recipient", "Parcel", lockerId = locker.id, compartmentId = "A"), "receipt") }
        val integration = Context(Actor("integration:hardware", "tenant", "hardware"), "standalone", setOf("packages.collect"))
        val event = LockerPickupEvent("event-1", parcel.id, "A", "recipient", clock.instant().toString())
        assertEquals(403, assertFailsWith<ApiException> { db.tx { service.trustedPickup(it, staff, event) } }.status)
        assertEquals("ignored", db.tx { service.trustedPickup(it, integration, event.copy(eventId = "old", occurredAt = "2020-01-01T00:00:00Z")) }.status)
        assertEquals("applied", db.tx { service.trustedPickup(it, integration, event) }.status)
        assertEquals("applied", db.tx { service.trustedPickup(it, integration, event) }.status)
        assertEquals("ignored", db.tx { service.trustedPickup(it, integration, event.copy(eventId = "later")) }.status)
        assertEquals(1, db.tx { service.get(it, recipient, parcel.id) }.history.count { it.action == "trusted_pickup_confirmed" })
        assertEquals(409, assertFailsWith<ApiException> {
            db.tx { service.trustedPickup(it, integration, event.copy(collectorId = "outsider")) }
        }.status)
    }
}
