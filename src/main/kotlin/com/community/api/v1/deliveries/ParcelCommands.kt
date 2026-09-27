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
    if (recipientKind == "membership" && member == null) c.fail(422, "RECIPIENT_REQUIRED", "Recipient membership is required")
    if (recipientKind == "node") {
        val node = nodeId?.let { c.store.get("node", it, condoId) } ?: c.fail(422, "RECIPIENT_REQUIRED", "Recipient node is required")
        if (!node.data.flag("receivesAsEntity")) c.fail(422, "NODE_NOT_RECIPIENT", "Node cannot receive deliveries as an entity")
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
