package com.community.api.v1.deliveries

import com.community.api.core.*
import com.community.api.v1.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class DeliveryHandlersTest {
    private val now = Instant.parse("2030-01-01T12:00:00Z")
    private val handlers = deliveryHandlers()
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
        user: String = "alice", staff: Boolean = false, deviceId: String? = null, at: Instant = now,
        query: Map<String, String> = emptyMap(), brand: String = "brand"): V1Response = db.scopedTx(null) { tx ->
        val store = V1Store(tx, "tenant", brand)
        val version = path["parcelId"]?.let { store.find("parcel", it)?.version }
        val principal = if (deviceId != null) V1Principal(deviceId = deviceId) else V1Principal(Actor(user, "tenant", "session"), staff = staff, permissions = setOf("*"))
        handlers.getValue(operation).handle(V1Context(tx, operation, "tenant", brand, UUID.randomUUID().toString(), input,
            path = path, query = query, headers = if (version == null) emptyMap() else mapOf("If-Match" to "\"$version\""),
            principal = principal, locationId = "condo", membership = if (staff || deviceId != null) null else store.get("membership", user), now = at)).also { validateResponse(operation, it) }
    }

    private fun validateResponse(operation: String, response: V1Response) {
        val definition = Contract.operations.find { it.id == operation }?.definition ?: return
        val schema = definition["responses"]?.jsonObject?.get(response.status.toString())?.jsonObject
            ?.get("content")?.jsonObject?.get("application/json")?.jsonObject?.get("schema")?.jsonObject ?: return
        Contract.validate(schema, response.body)
    }
    private fun frontDesk(db: Database): JsonObject = call(db, "registerParcel", obj("condominiumId" to "condo",
        "carrier" to "Postal", "storage" to "front_desk", "recipientMembershipId" to "alice"), staff = true).body.jsonObject

    @Test fun `manual report revokes credential without finalizing physical pickup`() = database().use { db ->
        val parcel = frontDesk(db)
        val id = parcel.string("id")!!
        val path = mapOf("parcelId" to id)
        val credential = call(db, "getPickupCredential", path = path).body.jsonObject
        assertTrue(credential.string("code")!!.matches(Regex("[0-9]{8}")))
        assertEquals(404, assertFailsWith<ApiException> { call(db, "getParcel", path = path, user = "bob") }.status)
        val before = db.tx { V1Store(it, "tenant", "brand").get("parcel", id).version }
        call(db, "getPickupCredential", path = path)
        assertEquals(before, db.tx { V1Store(it, "tenant", "brand").get("parcel", id).version })
        val manual = call(db, "markManualPickup", path = path).body.jsonObject
        assertEquals("manual", manual.string("status"))
        assertEquals(JsonNull, manual["collectedAt"])
        assertEquals("revoked", manual.string("credentialStatus"))
        assertEquals(410, assertFailsWith<ApiException> { call(db, "getPickupCredential", path = path) }.status)
        val reversed = call(db, "undoManualPickup", path = path).body.jsonObject
        assertEquals("waiting", reversed.string("status"))
        assertEquals("revoked", reversed.string("credentialStatus"))
        val handed = call(db, "handoverParcel", obj("collectorMembershipId" to "alice", "identityChecked" to true), path, staff = true).body.jsonObject
        assertEquals("collected", handed.string("status"))
        assertEquals("consumed", handed.string("credentialStatus"))
        assertEquals(now.toString(), handed.string("collectedAt"))
        assertEquals(409, assertFailsWith<ApiException> { call(db, "undoManualPickup", path = path) }.status)
    }

    @Test fun `delegation revokes old code and reissue binds new code to active delegate`() = database().use { db ->
        val path = mapOf("parcelId" to frontDesk(db).string("id")!!)
        val original = call(db, "getPickupCredential", path = path).body.jsonObject.string("code")!!
        call(db, "addParcelDelegate", obj("membershipId" to "bob"), path)
        assertEquals(410, assertFailsWith<ApiException> { call(db, "getPickupCredential", path = path) }.status)
        val replacement = call(db, "adminReissuePickupCredential", obj("membershipId" to "bob"), path, staff = true).body.jsonObject
        assertNotEquals(original, replacement.string("code"))
        assertEquals(403, assertFailsWith<ApiException> { call(db, "getPickupCredential", path = path) }.status)
        assertEquals(replacement.string("code"), call(db, "getPickupCredential", path = path, user = "bob").body.jsonObject.string("code"))
        assertEquals(422, assertFailsWith<ApiException> { call(db, "handoverParcel", obj("code" to original, "identityChecked" to false), path, staff = true) }.status)
        val handed = call(db, "handoverParcel", obj("code" to replacement.string("code"), "identityChecked" to false), path, staff = true).body.jsonObject
        assertEquals("bob", handed["collectedBy"]!!.jsonObject.string("membershipId"))
    }

    @Test fun `hardware scope deduplication credential checks and physical occupancy are enforced`() = database().use { db ->
        val device = call(db, "adminCreateDevice", obj("kind" to "locker", "name" to "Terminal"), staff = true).body.jsonObject
        val deviceId = device["device"]!!.jsonObject.string("id")!!
        assertTrue(device.string("apiKey")!!.startsWith("$deviceId."))
        val lockerId = call(db, "adminCreateLocker", obj("name" to "Locker", "deviceId" to deviceId), staff = true).body.jsonObject.string("id")!!
        val lockerPath = mapOf("lockerId" to lockerId)
        call(db, "adminSetCompartments", obj("compartments" to listOf(obj("code" to "A1", "size" to "M"))), lockerPath, staff = true)
        val parcel = call(db, "registerParcel", obj("condominiumId" to "condo", "carrier" to "Postal", "storage" to "locker",
            "recipientMembershipId" to "alice", "lockerId" to lockerId, "compartmentCode" to "A1"), staff = true).body.jsonObject
        val parcelPath = mapOf("parcelId" to parcel.string("id")!!)
        assertEquals(503, assertFailsWith<ApiException> { call(db, "getPickupCredential", path = parcelPath) }.status)
        val heartbeat = obj("events" to listOf(obj("eventId" to UUID.randomUUID().toString(), "type" to "heartbeat", "occurredAt" to now.toString())))
        call(db, "ingestLockerEvents", heartbeat, lockerPath, deviceId = deviceId)
        assertEquals(404, assertFailsWith<ApiException> { call(db, "ingestLockerEvents", heartbeat, lockerPath, deviceId = "foreign-device") }.status)
        val code = call(db, "getPickupCredential", path = parcelPath).body.jsonObject.string("code")!!
        val validation = call(db, "validatePickupCredential", obj("code" to code, "direction" to "exit"), lockerPath, deviceId = deviceId).body.jsonObject
        assertEquals(JsonPrimitive(true), validation["valid"])
        assertEquals(JsonPrimitive(false), validation["consumedNow"])
        val event = obj("events" to listOf(obj("eventId" to UUID.randomUUID().toString(), "type" to "pickup",
            "occurredAt" to now.plusSeconds(60).toString(), "compartmentCode" to "A1", "credentialCode" to code)))
        call(db, "markManualPickup", path = parcelPath, at = now.plusSeconds(120))
        val first = call(db, "ingestLockerEvents", event, lockerPath, deviceId = deviceId, at = now.plusSeconds(180)).body.jsonObject
        assertEquals("accepted", first["results"]!!.jsonArray.single().jsonObject.string("result"))
        val second = call(db, "ingestLockerEvents", event, lockerPath, deviceId = deviceId, at = now.plusSeconds(180)).body.jsonObject
        assertEquals("duplicate", second["results"]!!.jsonArray.single().jsonObject.string("result"))
        assertEquals("collected", call(db, "getParcel", path = parcelPath).body.jsonObject.string("status"))
        val compartments = call(db, "listCompartments", path = lockerPath, deviceId = deviceId).body.jsonObject["compartments"]!!.jsonArray
        assertEquals("free", compartments.single().jsonObject.string("status"))
        assertFalse(db.tx { V1Store(it, "tenant", "brand").list("locker_event").any { row -> row.data.toString().contains(code) } })
    }


    @Test fun `metrics count physical pickups without counting manual reports as collection`() = database().use { db ->
        val physical = frontDesk(db).string("id")!!
        val manual = frontDesk(db).string("id")!!
        call(db, "handoverParcel", obj("collectorMembershipId" to "alice", "identityChecked" to true), mapOf("parcelId" to physical), staff = true, at = now.plusSeconds(600))
        call(db, "markManualPickup", path = mapOf("parcelId" to manual), at = now.plusSeconds(300))
        val metrics = call(db, "getParcelMetrics", at = now.plusSeconds(900), query = mapOf("since" to now.minusSeconds(1).toString(), "until" to now.plusSeconds(900).toString())).body.jsonObject
        assertEquals(JsonPrimitive(2), metrics["totalReceived"])
        assertEquals(JsonPrimitive(1), metrics["physicalPickupCount"])
        assertEquals(600.0, metrics["averagePickupDurationSeconds"]!!.jsonPrimitive.double)
        assertEquals(JsonPrimitive(true), metrics["complete"])
    }

    @Test fun `parcel snapshots exclude new deliveries and cohabitants need explicit delegation`() = database().use { db ->
        val firstId = frontDesk(db).string("id")!!
        val secondId = frontDesk(db).string("id")!!
        val page = call(db, "listParcels", query = mapOf("limit" to "1")).body.jsonObject
        val first = page["items"]!!.jsonArray.single().jsonObject.string("id")!!
        val cursor = page["pageInfo"]!!.jsonObject.string("nextCursor")!!
        frontDesk(db)
        val next = call(db, "listParcels", query = mapOf("limit" to "1", "cursor" to cursor)).body.jsonObject
        val second = next["items"]!!.jsonArray.single().jsonObject.string("id")!!
        assertEquals(setOf(firstId, secondId), setOf(first, second))
        assertEquals(JsonNull, next["page"]!!.jsonObject["nextCursor"])
        assertTrue(call(db, "listParcels", user = "bob").body.jsonObject["items"]!!.jsonArray.isEmpty())
        call(db, "addParcelDelegate", obj("membershipId" to "bob"), mapOf("parcelId" to firstId))
        assertEquals(firstId, call(db, "listParcels", user = "bob").body.jsonObject["items"]!!.jsonArray.single().jsonObject.string("id"))
    }
    @Test fun `support issues respect ownership and trim minimum length`() = database().use { db ->
        val parcel = frontDesk(db).string("id")!!
        assertEquals(422, assertFailsWith<ApiException> { call(db, "createSupportIssue", obj("parcelId" to parcel, "message" to "     short     ")) }.status)
        val created = call(db, "createSupportIssue", obj("parcelId" to parcel, "message" to "A encomenda não estava na portaria.")).body.jsonObject
        assertEquals("received", created.string("status"))
        assertEquals(404, assertFailsWith<ApiException> { call(db, "getSupportIssue", path = mapOf("issueId" to created.string("id")!!), user = "bob") }.status)
    }
}
