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
            if (device.data.text("lastSeenAt")?.let(::instant)?.isAfter(at) != true)
                c.store.update(device, device.data.changed("lastSeenAt" to JsonPrimitive(at.toString())))
        }
        return
    }
    val code = event.text("compartmentCode") ?: c.fail(422, "COMPARTMENT_REQUIRED", "Compartment is required for this event")
    val compartment = locker.data.array("compartments").map { it.jsonObject }.find { it.text("code") == code }
        ?: c.fail(404, "COMPARTMENT_UNKNOWN", "Compartment not found")
    val parcel = compartment.text("parcelId")?.let { c.store.get("parcel", it, locker.locationId) }
    if (type == "pickup") {
        val row = parcel ?: c.fail(422, "PARCEL_UNKNOWN", "No parcel is linked to this compartment")
        if (at.isBefore(instant(row.data.text("depositedAt")))) c.fail(422, "STALE_EVENT", "Pickup precedes deposit")
        c.outstanding(row)
        val credential = c.historicalCredential(row, event.text("credentialCode"), at)
            ?: c.fail(422, "CREDENTIAL_INVALID", "Pickup credential was not valid at the event timestamp")
        val memberId = credential.data.text("membershipId") ?: c.fail(422, "COLLECTOR_UNKNOWN", "Credential has no collector")
        val member = c.store.get("membership", memberId, locker.locationId)
        if (member.data.text("status") != "active") c.fail(422, "COLLECTOR_INACTIVE", "Collector membership is inactive")
        collected(c, row, memberId, at)
        val currentCredential = c.store.get("pickup_credential", credential.id)
        c.store.update(currentCredential, currentCredential.data.changed("consumedAt" to JsonPrimitive(at.toString())))
        return
    }
    val lastAt = compartment.text("lastEventAt")?.let(::instant)
    if (lastAt != null && at.isBefore(lastAt)) return
    val newState = when (type) {
        "deposit" -> if (parcel == null) "reserved" else "occupied"
        "compartment_freed" -> {
            if (parcel != null && parcel.data.text("status") in setOf("waiting", "manual")) c.fail(409, "PARCEL_NOT_COLLECTED", "A pickup event must confirm collection")
            "free"
        }
        "fault", "door_forced" -> "faulty"
        "door_opened", "door_closed" -> compartment.text("status")!!
        else -> c.fail(422, "EVENT_TYPE_UNKNOWN", "Unsupported event type")
    }
    val next = compartment.changed("status" to JsonPrimitive(newState), "lastEventAt" to JsonPrimitive(at.toString()),
        "updatedAt" to JsonPrimitive(c.now.toString()), "parcelId" to if (newState == "free") JsonNull else compartment["parcelId"] ?: JsonNull)
    c.store.update(locker, locker.data.changed("compartments" to JsonArray(locker.data.array("compartments").map {
        if (it.jsonObject.text("code") == code) next else it
    })))
}
