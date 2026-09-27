package com.community.api.v1.deliveries

import com.community.api.core.ApiException
import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*

internal fun validateCredential(c: V1Context): V1Response {
    val locker = c.lockerRecord()
    val supplied = c.input.text("code") ?: c.input.text("qrPayload") ?: c.fail(422, "VALIDATION_ERROR", "A code or QR payload is required")
    if (c.input.text("code") != null && c.input.text("qrPayload") != null) c.fail(422, "VALIDATION_ERROR", "Supply only one credential representation")
    val row = c.store.list("parcel", locker.locationId).firstOrNull {
        it.data.text("lockerId") == locker.id && credentialMatches(c, it, supplied)
    }
    val valid = row != null && locker.data.flag("available")
    return V1Response(obj("valid" to valid, "kind" to if (valid) "pickup" else null,
        "reason" to if (valid) null else "unknown", "parcelId" to row?.id.takeIf { valid },
        "compartmentCode" to row?.data?.text("compartmentCode").takeIf { valid },
        "inviteId" to null, "visitorName" to null, "unitLabel" to null,
        "node" to if (valid) c.node(row!!.data.text("nodeId")) else JsonNull, "consumedNow" to false))
}

internal fun lockerEvents(c: V1Context): V1Response {
    val locker = c.lockerRecord()
    val events = c.input.array("events")
    if (events.isEmpty() || events.size > 200) c.fail(422, "VALIDATION_ERROR", "Send between 1 and 200 events")
    val acknowledgements = events.map { event ->
        val input = event.jsonObject
        val eventId = input.text("eventId")!!
        val key = c.hash("${locker.id}:$eventId")
        val fingerprint = c.hash(input.toString())
        val existing = c.store.find("locker_event", key)
        if (existing != null) {
            if (existing.data.text("fingerprint") == fingerprint)
                obj("eventId" to eventId, "result" to "duplicate", "reason" to existing.data["reason"])
            else obj("eventId" to eventId, "result" to "rejected", "reason" to "event_id_reused")
        } else {
            val reason = try { applyLockerEvent(c, c.store.get("locker", locker.id), input); null }
                catch (failure: ApiException) { failure.code.lowercase() }
            val result = if (reason == null) "accepted" else "rejected"
            val stored = c.store.create("locker_event", obj("eventId" to eventId, "lockerId" to locker.id, "fingerprint" to fingerprint,
                "type" to input.text("type"), "occurredAt" to input.text("occurredAt"), "compartmentCode" to input["compartmentCode"],
                "result" to result, "reason" to reason, "parcelExternalRef" to input["parcelExternalRef"],
                "payloadEncrypted" to c.seal((input["payload"] ?: JsonNull).toString())), locker.locationId, id = key)
            c.audit("locker.event_$result", stored)
            obj("eventId" to eventId, "result" to result, "reason" to reason)
        }
    }
    return V1Response(obj("results" to acknowledgements))
}

private fun applyLockerEvent(c: V1Context, locker: Record, event: JsonObject) {
    val at = instant(event.text("occurredAt"))
    if (at.isAfter(c.now.plusSeconds(300))) c.fail(422, "CLOCK_SKEW", "Hardware event timestamp is in the future")
    val type = event.text("type")!!
    if (type == "heartbeat") {
        val previous = locker.data.text("lastHeartbeatAt")?.let(::instant)
        if (previous == null || at.isAfter(previous)) c.store.update(locker, locker.data.changed("lastHeartbeatAt" to JsonPrimitive(at.toString())))
        c.principal?.deviceId?.let { id ->
            val device = c.store.get("device", id, locker.locationId)
