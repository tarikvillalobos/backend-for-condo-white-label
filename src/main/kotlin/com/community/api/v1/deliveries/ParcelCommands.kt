package com.community.api.v1.deliveries

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID

internal fun registerParcel(c: V1Context): V1Response {
    validatePhotos(c)
    val condoId = c.condo()
    c.store.get("condominium", condoId)
    val membershipId = c.input.text("recipientMembershipId")
    val member = membershipId?.let(c::member)
    val nodeId = c.input.text("nodeId") ?: member?.data?.text("nodeId")
    val recipientKind = c.input.text("recipientKind") ?: if (member != null) "membership" else "node"
    if (recipientKind == "membership" && member == null) c.fail(422, "VALIDATION_ERROR", "Recipient membership is required")
    if (recipientKind == "node") {
        val node = nodeId?.let { c.store.get("node", it, condoId) } ?: c.fail(422, "VALIDATION_ERROR", "Recipient node is required")
        if (!node.data.flag("receivesAsEntity")) c.fail(422, "NODE_NOT_ADDRESSABLE", "Node cannot receive deliveries as an entity")
    }
    nodeId?.let { c.store.get("node", it, condoId) }
    val carrier = c.input.text("carrier")?.trim().orEmpty()
    if (carrier.isEmpty()) c.fail(422, "VALIDATION_ERROR", "Carrier is required")
    val notificationEnabled = member != null && (member.ownerId?.let { c.store.find("profile", it) }?.data?.get("preferences") as? JsonObject)?.get("inApp") != JsonPrimitive(false)
    val id = UUID.randomUUID().toString()
    val deadlineHours = c.input.int("deadlineHours") ?: 72
    if (deadlineHours < 1) c.fail(422, "VALIDATION_ERROR", "Deadline must be positive")
    val deadline = c.now.plusSeconds(deadlineHours.toLong() * 3600)
    val storage = c.input.text("storage")!!
    if (storage == "locker") occupy(c, id, condoId) else if (c.input.text("lockerId") != null || c.input.text("compartmentCode") != null)
        c.fail(422, "VALIDATION_ERROR", "Front-desk storage cannot reference a locker compartment")
    var data = c.input.changed("carrier" to JsonPrimitive(carrier), "recipientKind" to JsonPrimitive(recipientKind),
        "membershipId" to value(member?.id), "nodeId" to value(nodeId), "status" to JsonPrimitive("waiting"),
        "depositedAt" to JsonPrimitive(c.now.toString()), "deadline" to JsonPrimitive(deadline.toString()),
        "manualAt" to JsonNull, "collectedAt" to JsonNull, "collectedBy" to JsonNull,
        "notifiedAt" to (if (notificationEnabled) JsonPrimitive(c.now.toString()) else JsonNull), "delegates" to JsonArray(emptyList()),
        "registeredByName" to value(c.tx.get("account", c.userId, c.tenantId)?.data?.text("name")),
        "timeline" to JsonArray(listOfNotNull(obj("type" to "deposited", "at" to c.now.toString()),
            if (notificationEnabled) obj("type" to "notification_available", "at" to c.now.toString()) else null)), "credentialStatus" to JsonPrimitive("unverified"))
    if (member != null) data = c.credentialData(data, member.id, deadline, id)
    val row = c.store.create("parcel", data, condoId, member?.ownerId, id)
    c.notifyParcel(row, "Encomenda recebida")
    c.audit("parcel.registered", row)
    return V1Response(c.parcelView(row), 201)
}

private fun occupy(c: V1Context, parcelId: String, condoId: String) {
    val lockerId = c.input.text("lockerId") ?: c.fail(422, "VALIDATION_ERROR", "Locker storage requires a locker")
    val code = c.input.text("compartmentCode") ?: c.fail(422, "VALIDATION_ERROR", "Locker storage requires a compartment")
    val locker = c.store.get("locker", lockerId, condoId)
    if (!locker.data.flag("available")) c.fail(409, "LOCKER_UNAVAILABLE", "Locker is unavailable")
    val compartment = locker.data.array("compartments").map { it.jsonObject }.find { it.text("code") == code }
        ?: c.fail(404, "RESOURCE_NOT_FOUND", "Compartment not found")
    if (compartment.text("status") != "free" || compartment.text("parcelId") != null) c.fail(409, "COMPARTMENT_OCCUPIED", "Compartment is unavailable")
    val compartments = locker.data.array("compartments").map {
        if (it.jsonObject.text("code") == code) it.jsonObject.changed("status" to JsonPrimitive("occupied"),
            "parcelId" to JsonPrimitive(parcelId), "updatedAt" to JsonPrimitive(c.now.toString())) else it
    }
    c.store.update(locker, locker.data.changed("compartments" to JsonArray(compartments)))
}

internal fun updateParcel(c: V1Context): V1Response {
    validatePhotos(c)
    val row = c.parcel()
    if (c.operationId == "adminUpdateParcel" || c.header("If-Match") != null) c.requireVersion(row)
    if (row.data.text("status") != "waiting") c.fail(409, "PARCEL_NOT_EDITABLE", "Only waiting parcels can be edited")
    val changingDeadline = c.operationId == "adminExtendParcelDeadline"
    var data = if (changingDeadline) {
        val deadline = instant(c.input.text("newDeadline"))
        if (!deadline.isAfter(instant(row.data.text("deadline"))) || !deadline.isAfter(c.now)) c.fail(422, "VALIDATION_ERROR", "New deadline must extend the current deadline")
        row.data.changed("deadline" to JsonPrimitive(deadline.toString()))
    } else JsonObject(row.data + c.input.filterKeys { it != "reason" })
    var owner = row.ownerId
    if (!changingDeadline && (c.input.containsKey("recipientMembershipId") || c.input.containsKey("nodeId") || c.input.containsKey("recipientKind"))) {
        val kind = data.text("recipientKind") ?: "membership"
        val member = if (kind == "membership") c.member(data.text("recipientMembershipId") ?: c.fail(422, "RECIPIENT_REQUIRED", "Recipient membership is required")) else null
        val nodeId = data.text("nodeId") ?: member?.data?.text("nodeId")
        val node = nodeId?.let { c.store.get("node", it, row.locationId) }
        if (kind == "node" && node?.data?.flag("receivesAsEntity") != true) c.fail(422, "NODE_NOT_RECIPIENT", "Node cannot receive deliveries")
        c.revokeCredential(row.data)
        data = data.changed("membershipId" to value(member?.id), "nodeId" to value(nodeId), "credentialStatus" to JsonPrimitive("revoked"),
            "sealedCode" to JsonNull, "credentialHash" to JsonNull, "delegates" to JsonArray(emptyList()))
        owner = member?.ownerId
    }
    if (data.text("carrier").isNullOrBlank()) c.fail(422, "VALIDATION_ERROR", "Carrier is required")
    val updated = c.store.update(row, data)
    if (owner != row.ownerId || c.input.containsKey("recipientMembershipId")) c.notifyParcel(updated, "Encomenda atribuída a você")
    c.audit(if (changingDeadline) "parcel.deadline_extended" else "parcel.updated", updated)
    return V1Response(c.parcelView(updated, true))
}

internal fun closeParcel(c: V1Context): V1Response {
    val row = c.parcel()
    if (c.header("If-Match") != null) c.requireVersion(row)
    c.outstanding(row)
    c.releaseCompartment(row)
    c.revokeCredential(row.data)
    val state = if (c.operationId == "adminReturnParcel") "returned" else "cancelled"
    val updated = c.store.update(row, row.data.changed("status" to JsonPrimitive(state), "credentialStatus" to JsonPrimitive("revoked"),
        "sealedCode" to JsonNull, "credentialHash" to JsonNull, "closureReason" to (c.input["reason"] ?: JsonNull)))
    c.audit("parcel.$state", updated)
    c.notifyParcel(updated, if (state == "returned") "Encomenda devolvida" else "Encomenda cancelada")
    return V1Response(c.parcelView(updated, true))
}

internal fun credentialMatches(c: V1Context, row: Record, supplied: String?, at: java.time.Instant = c.now): Boolean {
    if (supplied.isNullOrEmpty() || row.data.text("credentialStatus") != "active" || row.data.text("status") != "waiting") return false
    val hash = row.data.text("credentialHash") ?: return false
    val expires = row.data.text("credentialExpiresAt")?.let(::instant) ?: return false
    return at.isBefore(expires) && MessageDigest.isEqual(hash.toByteArray(), c.hash(supplied).toByteArray())
}

internal fun collected(c: V1Context, row: Record, collector: String, at: java.time.Instant): Record {
    c.releaseCompartment(row, at)
    c.revokeCredential(row.data, at)
    val updated = c.store.update(row, row.data.changed("status" to JsonPrimitive("collected"), "collectedAt" to JsonPrimitive(at.toString()),
        "collectedBy" to JsonPrimitive(collector), "credentialStatus" to JsonPrimitive("consumed"), "sealedCode" to JsonNull,
        "credentialHash" to JsonNull, "timeline" to timeline(row.data, "physical_pickup", at)))
    c.audit("parcel.collected", updated)
    c.notifyParcel(updated, "Retirada da encomenda confirmada")
    return updated
}

internal fun handover(c: V1Context): V1Response {
    val row = c.parcel()
    if (c.header("If-Match") != null) c.requireVersion(row)
    c.outstanding(row)
    if (row.data.text("storage") != "front_desk") c.fail(409, "WRONG_STORAGE", "Locker pickups require a hardware event")
    val supplied = c.input.text("code") ?: c.input.text("qrPayload")
    val validCode = credentialMatches(c, row, supplied)
    val collector = if (validCode) row.data.text("credentialMemberId")!! else c.input.text("collectorMembershipId")
        ?: c.fail(422, "COLLECTOR_REQUIRED", "Identify the collector or provide a valid credential")
    val member = c.member(collector)
    val nodeRecipient = row.data.text("recipientKind") == "node" && c.nodePath(row.data.text("nodeId")).any { it.jsonObject.text("id") == member.data.text("nodeId") }
    if (row.data.text("membershipId") != collector && row.data.array("delegates").none { it.jsonPrimitive.content == collector } && !nodeRecipient)
        c.fail(403, "FORBIDDEN", "Collector is not authorized for this parcel")
    if (!validCode && !c.input.flag("identityChecked")) c.fail(422, "IDENTITY_REQUIRED", "Check collector identity when a credential is unavailable")
    return V1Response(c.parcelView(collected(c, row, collector, c.now)))
}

internal fun reissue(c: V1Context): V1Response {
    val row = c.parcel()
    if (c.header("If-Match") != null) c.requireVersion(row)
    if (row.data.text("status") != "waiting") c.fail(409, "PARCEL_NOT_EDITABLE", "Only waiting parcels can receive a credential")
    val id = c.input.text("membershipId") ?: c.fail(422, "COLLECTOR_REQUIRED", "Collector membership is required")
    val member = c.member(id)
    val nodeRecipient = row.data.text("recipientKind") == "node" && c.nodePath(row.data.text("nodeId")).any { it.jsonObject.text("id") == member.data.text("nodeId") }
    if (row.data.text("membershipId") != id && row.data.array("delegates").none { it.jsonPrimitive.content == id } && !nodeRecipient)
        c.fail(422, "DELEGATE_NOT_ELIGIBLE", "Collector must be the recipient or an authorized delegate")
    val updated = c.store.update(row, c.credentialData(row.data, id, instant(row.data.text("deadline")), row.id))
    c.audit("parcel.credential_reissued", updated)
    return V1Response(c.pickupView(updated), headers = mapOf("ETag" to "\"${updated.version}\""))
}

internal fun resendNotice(c: V1Context): V1Response {
    val row = c.parcel()
    c.outstanding(row)
    val channels = c.input.array("channels").map { it.jsonPrimitive.content }
    if (channels.any { it != "email" }) c.fail(422, "CHANNEL_UNAVAILABLE", "Only configured delivery channels can be used")
    val memberId = row.data.text("membershipId") ?: c.fail(422, "RECIPIENT_REQUIRED", "Node recipient has no individual delivery channel")
    val member = c.member(memberId)
    val email = member.ownerId?.let { c.tx.get("account", it, c.tenantId)?.data?.text("email") }
        ?: c.fail(422, "CHANNEL_UNAVAILABLE", "Recipient email is unavailable")
    c.enqueueMail(email, "Encomenda aguardando retirada", "Uma encomenda da transportadora ${row.data.text("carrier")} aguarda retirada.")
    c.notifyParcel(row, "Encomenda aguardando retirada")
    c.audit("parcel.notice_queued", row)
    return V1Response(status = 202)
}

private fun validatePhotos(c: V1Context) {
    c.input.array("photoKeys").forEach { value ->
        val upload = c.store.get("upload", value.jsonPrimitive.content)
        if (upload.ownerId != c.userId) c.fail(404, "NOT_FOUND", "Upload not found")
        if (upload.data.text("status") != "complete") c.fail(409, "UPLOAD_INCOMPLETE", "Complete the upload first")
    }
}
