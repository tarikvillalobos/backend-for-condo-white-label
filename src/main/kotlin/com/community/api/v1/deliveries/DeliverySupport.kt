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
internal fun V1Context.condo(): String = locationId ?: input.text("condominiumId") ?: fail(404, "NOT_FOUND", "Condominium not found")
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
        if (own && !canReadParcel(row)) fail(404, "NOT_FOUND", "Parcel not found")
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
    if (row.data.text("membershipId") != membershipId) fail(403, "FORBIDDEN", "Only the recipient can perform this action")
}
internal fun V1Context.outstanding(row: Record) {
    if (row.data.text("status") !in setOf("waiting", "manual")) fail(409, "PARCEL_NOT_EDITABLE", "Parcel is already closed")
}
internal fun timeline(data: JsonObject, type: String, at: Instant): JsonArray =
    JsonArray(data.array("timeline") + obj("type" to type, "at" to at.toString()))
internal fun V1Context.credentialData(data: JsonObject, memberId: String, deadline: Instant): JsonObject {
    val code = (0..99999999).let { SecureRandom().nextInt(100000000).toString().padStart(8, '0') }
    val expiry = minOf(deadline, now.plusSeconds(86400))
    if (!expiry.isAfter(now)) fail(409, "PARCEL_EXPIRED", "Extend the parcel deadline before issuing a credential")
    return data.changed("credentialStatus" to JsonPrimitive("active"), "credentialMemberId" to JsonPrimitive(memberId),
        "sealedCode" to JsonPrimitive(seal(code)), "credentialHash" to JsonPrimitive(hash(code)), "credentialExpiresAt" to JsonPrimitive(expiry.toString()))
}
internal fun V1Context.notifyParcel(row: Record, title: String) {
    val memberId = row.data.text("membershipId") ?: return
    val membership = store.get("membership", memberId, row.locationId)
    store.create("notification", obj("membershipId" to memberId, "kind" to "parcel", "title" to title,
        "body" to "Transportadora: ${row.data.text("carrier")}", "referenceId" to row.id, "readAt" to null), row.locationId, membership.ownerId)
}
internal fun V1Context.releaseCompartment(row: Record) {
    val lockerId = row.data.text("lockerId") ?: return
    val locker = store.get("locker", lockerId, row.locationId)
    val compartments = locker.data.array("compartments").map { element ->
        val compartment = element.jsonObject
        if (compartment.text("parcelId") == row.id) compartment.changed("status" to JsonPrimitive("free"), "parcelId" to JsonNull,
            "updatedAt" to JsonPrimitive(now.toString())) else compartment
    }
    store.update(locker, locker.data.changed("compartments" to JsonArray(compartments)))
}
