package com.community.api.v1.deliveries

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

internal fun lockerHandlers(): Map<String, V1Handler> = mapOf(
    "adminListLockers" to V1Handler { c -> V1Response(obj("items" to c.store.list("locker", c.condo()).map { c.lockerView(it) })) },
    "adminCreateLocker" to V1Handler(::saveLocker), "adminUpdateLocker" to V1Handler(::saveLocker),
    "adminSetCompartments" to V1Handler(::setCompartments), "adminUpdateCompartment" to V1Handler(::updateCompartment),
    "listCompartments" to V1Handler { c -> V1Response(obj("lockerId" to c.path.getValue("lockerId"), "compartments" to c.lockerRecord().data.array("compartments").map { c.project("Compartment", it.jsonObject) })) },
    "adminListDevices" to V1Handler { c -> V1Response(obj("items" to c.store.list("device", c.condo()).map { c.deviceView(it) })) },
    "adminCreateDevice" to V1Handler(::saveDevice), "adminRotateDeviceKey" to V1Handler(::saveDevice),
    "adminRevokeDevice" to V1Handler(::revokeDevice), "ingestLockerEvents" to V1Handler(::lockerEvents),
    "validatePickupCredential" to V1Handler(::validateCredential),
    "adminOpenCompartment" to V1Handler { c ->
        val locker = c.lockerRecord()
        val code = c.path.getValue("compartmentCode")
        val compartment = locker.data.array("compartments").map { it.jsonObject }.firstOrNull { it.text("code") == code }
            ?: c.fail(404, "RESOURCE_NOT_FOUND", "Compartment not found")
        if (!locker.data.flag("available") || compartment.text("status") in setOf("faulty", "disabled"))
            c.fail(409, "COMPARTMENT_UNAVAILABLE", "Compartimento indisponível")
        val device = locker.data.text("deviceId")?.let { c.store.get("device", it, locker.locationId) }
            ?: c.fail(409, "DEVICE_UNKNOWN", "Locker sem equipamento associado")
        if (device.data.text("status") != "active") c.fail(409, "DEVICE_REVOKED", "Equipamento inativo")
        val reason = c.input.text("reason")?.trim()?.takeIf { it.length >= 3 }
            ?: c.fail(422, "VALIDATION_ERROR", "Informe o motivo da abertura")
    },
)

internal fun lockerOnline(data: JsonObject, now: Instant): Boolean = data.text("lastHeartbeatAt")?.let {
    val at = instant(it)
    !at.isAfter(now.plusSeconds(300)) && at.plusSeconds(180).isAfter(now)
} ?: false

internal fun V1Context.lockerRecord(): Record = store.get("locker", path.getValue("lockerId"), locationId).also { locker ->
    val deviceId = principal?.deviceId
    if (deviceId != null && locker.data.text("deviceId") != deviceId) fail(404, "RESOURCE_NOT_FOUND", "Locker not found")
    if (deviceId == null) requirePermission("lockers.read", "lockers.manage", "parcels.receive")
}

private fun V1Context.lockerView(row: Record): JsonObject {
    val data = row.data
    val compartments = data.array("compartments").map { it.jsonObject }
    return obj("id" to row.id, "name" to data.text("name"), "address" to data.text("address").orEmpty(), "node" to node(data.text("nodeId")),
        "deviceId" to data["deviceId"], "available" to data.flag("available"), "online" to (lockerOnline(data, now) && data.text("deviceId")?.let { store.find("device", it)?.data?.text("status") == "active" } == true),
        "lastHeartbeatAt" to data["lastHeartbeatAt"], "occupancy" to obj("total" to compartments.size,
            "occupied" to compartments.count { it.text("status") == "occupied" }, "faulty" to compartments.count { it.text("status") == "faulty" },
            "disabled" to compartments.count { it.text("status") == "disabled" }))
}

private fun saveLocker(c: V1Context): V1Response {
    val previous = c.path["lockerId"]?.let { c.store.get("locker", it, c.condo()) }
    if (c.header("If-Match") != null) previous?.let(c::requireVersion)
    var data = JsonObject((previous?.data ?: obj("available" to true, "compartments" to JsonArray(emptyList()), "lastHeartbeatAt" to null)) + c.input)
    if (data.text("name").isNullOrBlank()) c.fail(422, "VALIDATION_ERROR", "Locker name is required")
    data.text("nodeId")?.let { c.store.get("node", it, c.condo()) }
    val device = data.text("deviceId")?.let { c.store.get("device", it, c.condo()) }
    if (device != null && (device.data.text("type") != "locker" || device.data.text("status") != "active"))
        c.fail(422, "VALIDATION_ERROR", "Locker requires an active locker device")
    if (device != null && c.store.list("locker", c.condo()).any { it.id != previous?.id && it.data.text("deviceId") == device.id })
        c.fail(409, "DEVICE_IN_USE", "Device already belongs to another locker")
    data = data.changed("address" to value(c.store.get("condominium", c.condo()).data.text("address")))
    val saved = if (previous == null) c.store.create("locker", data, c.condo()) else c.store.update(previous, data)
    device?.let { c.store.update(it, it.data.changed("lockerId" to JsonPrimitive(saved.id))) }
    c.audit(if (previous == null) "locker.created" else "locker.updated", saved)
    return V1Response(c.lockerView(saved), if (previous == null) 201 else 200)
}

private fun setCompartments(c: V1Context): V1Response {
    val locker = c.lockerRecord()
    if (c.header("If-Match") != null) c.requireVersion(locker)
    val definitions = c.input.array("compartments").map { it.jsonObject }
    if (definitions.isEmpty() || definitions.size > 300 || definitions.any { it.text("code").isNullOrBlank() } || definitions.map { it.text("code") }.distinct().size != definitions.size)
        c.fail(422, "VALIDATION_ERROR", "Compartment codes must be nonempty and unique")
    val previous = locker.data.array("compartments").map { it.jsonObject }.associateBy { it.text("code") }
    val codes = definitions.map { it.text("code") }.toSet()
    if (previous.values.any { (it.text("parcelId") != null || it.text("status") in setOf("occupied", "reserved")) && it.text("code") !in codes })
        c.fail(409, "COMPARTMENT_NOT_EMPTY", "Occupied compartments cannot be removed")
    val compartments = definitions.map { definition ->
        val old = previous[definition.text("code")]
        if (old?.text("parcelId") != null && old.text("size") != definition.text("size")) c.fail(409, "COMPARTMENT_NOT_EMPTY", "Occupied compartment size cannot change")
        JsonObject((old ?: obj()) + obj("code" to definition.text("code"), "size" to definition.text("size"), "status" to (old?.text("status") ?: "free"),
            "parcelId" to old?.get("parcelId"), "updatedAt" to c.now.toString()))
    }
    val saved = c.store.update(locker, locker.data.changed("compartments" to JsonArray(compartments)))
    c.audit("locker.compartments_updated", saved)
    return V1Response(obj("lockerId" to locker.id, "compartments" to compartments.map { c.project("Compartment", it) }))
}

private fun updateCompartment(c: V1Context): V1Response {
    val locker = c.lockerRecord()
    if (c.header("If-Match") != null) c.requireVersion(locker)
    val code = c.path.getValue("compartmentCode")
    val previous = locker.data.array("compartments").map { it.jsonObject }.find { it.text("code") == code }
        ?: c.fail(404, "RESOURCE_NOT_FOUND", "Compartment not found")
    if (previous.text("parcelId") != null) c.fail(409, "COMPARTMENT_NOT_EMPTY", "Occupied compartments are managed through parcel operations")
    val updated = JsonObject(previous + c.input + obj("updatedAt" to c.now.toString()))
    val saved = c.store.update(locker, locker.data.changed("compartments" to JsonArray(locker.data.array("compartments").map {
        if (it.jsonObject.text("code") == code) updated else it
    })))
    c.audit("locker.compartment_updated", saved)
    return V1Response(c.project("Compartment", updated))
}

private fun V1Context.deviceView(row: Record): JsonObject = obj("id" to row.id, "kind" to row.data.text("type"),
    "name" to row.data.text("name"), "node" to node(row.data.text("nodeId")), "lastSeenAt" to row.data["lastSeenAt"],
    "keyRotatedAt" to row.data["keyRotatedAt"], "revokedAt" to row.data["revokedAt"], "metadata" to (row.data["metadata"] ?: obj()))

private fun saveDevice(c: V1Context): V1Response {
    val previous = c.path["deviceId"]?.let { c.store.get("device", it, c.condo()) }
    if (c.header("If-Match") != null) previous?.let(c::requireVersion)
    val deviceId = previous?.id ?: java.util.UUID.randomUUID().toString()
    val key = deviceId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    val input = if (previous == null) c.input else previous.data
    input.text("nodeId")?.let { c.store.get("node", it, c.condo()) }
    val data = JsonObject(input + obj("type" to (input.text("type") ?: input.text("kind")), "brandId" to c.brandId,
        "condominiumId" to c.condo(), "keyHash" to c.hash(key), "status" to "active", "keyRotatedAt" to c.now.toString(), "revokedAt" to null))
    val row = if (previous == null) c.store.create("device", data, c.condo(), id = deviceId) else c.store.update(previous, data)
    c.audit(if (previous == null) "device.created" else "device.key_rotated", row)
    return V1Response(obj("device" to c.deviceView(row), "apiKey" to key), if (previous == null) 201 else 200)
}

private fun revokeDevice(c: V1Context): V1Response {
    val row = c.store.get("device", c.path.getValue("deviceId"), c.condo())
    if (c.header("If-Match") != null) c.requireVersion(row)
    val saved = c.store.update(row, row.data.changed("status" to JsonPrimitive("revoked"), "revokedAt" to JsonPrimitive(c.now.toString()), "keyHash" to JsonNull))
    c.audit("device.revoked", saved)
    return V1Response(c.deviceView(saved))
}
