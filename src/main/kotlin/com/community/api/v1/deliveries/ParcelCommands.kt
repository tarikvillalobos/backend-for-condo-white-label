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
