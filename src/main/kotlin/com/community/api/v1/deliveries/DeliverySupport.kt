package com.community.api.v1.deliveries

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.time.Instant

internal fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.flag(name: String): Boolean = (get(name) as? JsonPrimitive)?.booleanOrNull == true
internal fun JsonObject.int(name: String): Int? = (get(name) as? JsonPrimitive)?.intOrNull
internal fun JsonObject.array(name: String): JsonArray = get(name) as? JsonArray ?: JsonArray(emptyList())
internal fun JsonObject.changed(vararg values: Pair<String, JsonElement>): JsonObject = JsonObject(this + values)
internal fun value(text: String?): JsonElement = text?.let(::JsonPrimitive) ?: JsonNull
internal fun instant(text: String?): Instant = try { Instant.parse(text) }
    catch (_: Exception) { throw com.community.api.core.ApiException(422, "VALIDATION_ERROR", "A valid timestamp is required") }
internal fun V1Context.condo(): String = locationId ?: input.text("condominiumId") ?: fail(404, "RESOURCE_NOT_FOUND", "Condominium not found")
internal fun V1Context.member(id: String): Record = store.get("membership", id, condo()).also {
    if (it.data.text("status") != "active") fail(422, "DELEGATE_NOT_ELIGIBLE", "An active membership in the same condominium is required")
}
internal fun V1Context.person(id: String?): JsonElement {
    if (id == null) return JsonNull
    val member = store.find("membership", id) ?: return JsonNull
    val account = member.ownerId?.let { tx.get("account", it, tenantId) }
    return obj("membershipId" to member.id, "name" to (member.data.text("name") ?: account?.data?.text("name").orEmpty()))
}
internal fun V1Context.node(id: String?): JsonElement {
    if (id == null) return JsonNull
    val row = store.get("node", id)
    return obj("id" to row.id, "type" to (row.data.text("type") ?: row.data.text("typeCode")), "label" to row.data.text("label"))
}
internal fun V1Context.nodePath(id: String?): JsonArray {
    val result = mutableListOf<JsonElement>()
    val seen = mutableSetOf<String>()
    var current = id
    while (current != null && seen.add(current)) {
        result.add(0, node(current))
        current = store.get("node", current).data.text("parentId")
    }
    return JsonArray(result)
}
internal fun V1Context.parcel(id: String = path.getValue("parcelId"), own: Boolean = membershipId != null): Record =
    store.get("parcel", id, locationId).also { row ->
        if (own && !canReadParcel(row)) fail(404, "RESOURCE_NOT_FOUND", "Parcel not found")
    }
internal fun V1Context.canReadParcel(row: Record, includeClosed: Boolean = false): Boolean {
    if (!includeClosed && row.data.text("status") in setOf("returned", "cancelled")) return false
    if (row.data.text("membershipId") == membershipId) return true
    if (row.data.array("delegates").any { it.jsonPrimitive.content == membershipId }) return true
    return row.data.text("recipientKind") == "node" && membership?.data?.text("nodeId")?.let { memberNode ->
        nodePath(row.data.text("nodeId")).any { it.jsonObject.text("id") == memberNode }
    } == true
}
internal fun V1Context.recipient(row: Record) {
    if (row.data.text("membershipId") != membershipId) fail(403, "ACCESS_DENIED", "Only the recipient can perform this action")
}
internal fun V1Context.outstanding(row: Record) {
    if (row.data.text("status") !in setOf("waiting", "manual")) fail(409, "PARCEL_NOT_EDITABLE", "Parcel is already closed")
}
internal fun timeline(data: JsonObject, type: String, at: Instant): JsonArray =
    JsonArray(data.array("timeline") + obj("type" to type, "at" to at.toString()))
internal fun V1Context.credentialData(data: JsonObject, memberId: String, deadline: Instant, parcelId: String): JsonObject {
    val occupied = store.list("parcel", locationId).filter { it.data.text("credentialStatus") == "active" }.mapNotNull { it.data.text("credentialHash") }.toSet()
    val code = generateSequence { SecureRandom().nextInt(100000000).toString().padStart(8, '0') }
        .take(20).firstOrNull { hash(it) !in occupied } ?: fail(503, "SERVICE_UNAVAILABLE", "Unable to allocate a unique credential")
    val expiry = minOf(deadline, now.plusSeconds(86400))
    if (!expiry.isAfter(now)) fail(409, "DEADLINE_INVALID", "Extend the parcel deadline before issuing a credential")
    revokeCredential(data)
    store.create("pickup_credential", obj("parcelId" to parcelId, "membershipId" to memberId, "hash" to hash(code),
        "issuedAt" to now.toString(), "expiresAt" to expiry.toString(), "revokedAt" to null, "consumedAt" to null), locationId, id = hash(code + parcelId + now.toString()))
    return data.changed("credentialStatus" to JsonPrimitive("active"), "credentialMemberId" to JsonPrimitive(memberId),
        "sealedCode" to JsonPrimitive(seal(code)), "credentialHash" to JsonPrimitive(hash(code)), "credentialExpiresAt" to JsonPrimitive(expiry.toString()))
}
internal fun V1Context.notifyParcel(row: Record, title: String) {
    val memberId = row.data.text("membershipId") ?: return
    val membership = store.get("membership", memberId, row.locationId)
    val profile = membership.ownerId?.let { store.find("profile", it) }
    if ((profile?.data?.get("preferences") as? JsonObject)?.get("inApp") == JsonPrimitive(false)) return
    store.create("notification", obj("membershipId" to memberId, "kind" to "parcel", "title" to title,
        "body" to "Transportadora: ${row.data.text("carrier")}", "referenceId" to row.id, "readAt" to null), row.locationId, membership.ownerId)
}
internal fun V1Context.releaseCompartment(row: Record, occurredAt: Instant = now) {
    val lockerId = row.data.text("lockerId") ?: return
    val locker = store.get("locker", lockerId, row.locationId)
    val compartments = locker.data.array("compartments").map { element ->
        val compartment = element.jsonObject
        if (compartment.text("parcelId") == row.id) compartment.changed("status" to JsonPrimitive("free"), "parcelId" to JsonNull,
            "updatedAt" to JsonPrimitive(now.toString()), "lastEventAt" to JsonPrimitive(maxOf(occurredAt, compartment.text("lastEventAt")?.let(::instant) ?: occurredAt).toString())) else compartment
    }
    store.update(locker, locker.data.changed("compartments" to JsonArray(compartments)))
}

internal fun V1Context.revokeCredential(data: JsonObject, consumedAt: Instant? = null) {
    val hash = data.text("credentialHash") ?: return
    store.list("pickup_credential", filters = mapOf("hash" to hash)).forEach { row ->
        if (row.data.text("revokedAt") == null && row.data.text("consumedAt") == null)
            store.update(row, row.data.changed((if (consumedAt == null) "revokedAt" else "consumedAt") to JsonPrimitive((consumedAt ?: now).toString())))
    }
}

internal fun V1Context.historicalCredential(parcel: Record, supplied: String?, at: Instant): Record? {
    if (supplied.isNullOrBlank()) return null
    return store.list("pickup_credential", filters = mapOf("parcelId" to parcel.id, "hash" to hash(supplied))).firstOrNull { row ->
        val data = row.data
        !at.isBefore(instant(data.text("issuedAt"))) && at.isBefore(instant(data.text("expiresAt"))) &&
            data.text("revokedAt")?.let { at.isBefore(instant(it)) } != false &&
            data.text("consumedAt")?.let { at.isBefore(instant(it)) } != false
    }
}
