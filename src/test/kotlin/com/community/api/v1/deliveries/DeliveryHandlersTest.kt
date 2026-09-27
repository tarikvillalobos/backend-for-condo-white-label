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
