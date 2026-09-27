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
        if (locker.data.array("compartments").none { it.jsonObject.text("code") == c.path["compartmentCode"] }) c.fail(404, "NOT_FOUND", "Compartment not found")
        c.fail(501, "PROVIDER_NOT_CONFIGURED", "Remote compartment opening requires a configured hardware provider")
    },
)

internal fun lockerOnline(data: JsonObject, now: Instant): Boolean = data.text("lastHeartbeatAt")?.let {
    val at = instant(it)
    !at.isAfter(now.plusSeconds(300)) && at.plusSeconds(180).isAfter(now)
} ?: false

internal fun V1Context.lockerRecord(): Record = store.get("locker", path.getValue("lockerId"), locationId).also { locker ->
    val deviceId = principal?.deviceId
    if (deviceId != null && locker.data.text("deviceId") != deviceId) fail(404, "NOT_FOUND", "Locker not found")
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
        c.fail(422, "INVALID_DEVICE", "Locker requires an active locker device")
    if (device != null && c.store.list("locker", c.condo()).any { it.id != previous?.id && it.data.text("deviceId") == device.id })
        c.fail(409, "DEVICE_ASSIGNED", "Device already belongs to another locker")
    data = data.changed("address" to value(c.store.get("condominium", c.condo()).data.text("address")))
    val saved = if (previous == null) c.store.create("locker", data, c.condo()) else c.store.update(previous, data)
    device?.let { c.store.update(it, it.data.changed("lockerId" to JsonPrimitive(saved.id))) }
