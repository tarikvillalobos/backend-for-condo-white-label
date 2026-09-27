package com.community.api.v1.community

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*

internal fun V1Context.visitor(row: Record): JsonObject = view("Visitor", row, obj(
    "document" to row.data.text("document")?.let { "***${it.takeLast(4)}" }, "phone" to row.data["phone"],
    "company" to row.data["company"], "vehiclePlate" to row.data["vehiclePlate"], "notes" to row.data["notes"],
))
internal fun V1Context.inviteStatus(row: Record): String = when {
    row.data.text("revokedAt") != null -> "revoked"
    (row.data.flag("singleUse") && row.data.number("usesCount") > 0) ||
        row.data.text("maxUses")?.toIntOrNull()?.let { row.data.number("usesCount") >= it } == true -> "used"
    !timestamp(row.data.text("validUntil")!!).isAfter(now) -> "expired"
    timestamp(row.data.text("validFrom")!!).isAfter(now) -> "scheduled"
    else -> "active"
}
internal fun V1Context.invite(row: Record): JsonObject {
    val visitorId = row.data.text("visitorId")!!
    val visitor = store.find("visitor", visitorId, locationId)?.let { visitor(it) } ?: row.data["visitorSnapshot"] ?: JsonNull
    return view("AccessInvite", row, obj("visitor" to visitor, "status" to inviteStatus(row), "usesCount" to row.data.number("usesCount"),
        "uses" to row.data.array("uses"), "maxUses" to row.data["maxUses"], "shareUrl" to null, "revokedAt" to row.data["revokedAt"],
        "hostName" to personName(row.ownerId), "node" to node(row.data.text("nodeId"))))
}
private fun V1Context.validateInvite(data: JsonObject) {
    val settings = store.find("condominium", location())?.data?.get("settings") as? JsonObject
    window(data, (settings?.number("invite_max_days", 7) ?: 7).toLong() * 86400)
    if (!timestamp(data.text("validUntil")!!).isAfter(now)) fail(422, "INVITE_EXPIRED", "O convite já expirou")
    data.array("allowedGates").forEach { gateId ->
        if (!store.get("gate", gateId.jsonPrimitive.content, locationId).data.flag("active", true))
            fail(422, "GATE_INACTIVE", "Portão inativo")
    }
}
private fun V1Context.createInvite(): V1Response {
    validateInvite(input)
    val visitorId = input.text("visitorId")
    val inline = input["visitor"] as? JsonObject
    if ((visitorId == null) == (inline == null)) fail(422, "VISITOR_REQUIRED", "Informe visitorId ou visitor")
    val visitor = if (visitorId != null) owned(store.get("visitor", visitorId, locationId))
