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
        else save("visitor", inline!!.merge(obj("nodeId" to unitId)))
    val row = save("access_invite", JsonObject(input - "visitor").merge(obj("visitorId" to visitor.id,
        "visitorSnapshot" to visitor(visitor), "nodeId" to unitId, "usesCount" to 0, "uses" to JsonArray(emptyList()), "revokedAt" to null)).merge(newAccessSecret()))
    return V1Response(invite(row), 201)
}
internal fun visitorHandlers(): Map<String, V1Handler> = mapOf(
    "listVisitors" to V1Handler { c -> c.listResponse("visitor", true) { c.visitor(it) } },
    "createVisitor" to V1Handler { c -> V1Response(c.visitor(c.save("visitor", c.input.merge(obj("nodeId" to c.unitId)))), 201) },
    "getVisitor" to V1Handler { c -> V1Response(c.visitor(c.record("visitor", "visitorId"))) },
    "updateVisitor" to V1Handler { c -> V1Response(c.visitor(c.change(c.record("visitor", "visitorId"), c.input))) },
    "deleteVisitor" to V1Handler { c ->
        val row = c.record("visitor", "visitorId")
        c.store.list("access_invite", c.locationId, filters = mapOf("visitorId" to row.id)).forEach { invite ->
            if (c.inviteStatus(invite) in setOf("scheduled", "active")) c.change(invite, obj("revokedAt" to now()), "access_invite.revoked")
        }
        c.remove(row)
    },
    "adminListVisitors" to V1Handler { c -> c.listResponse("visitor") { row ->
        val latest = c.store.list("access_event", c.locationId, filters = mapOf("visitorId" to row.id)).maxByOrNull { it.data.text("occurredAt").orEmpty() }
        obj("visitor" to c.visitor(row), "hostName" to c.personName(row.ownerId), "node" to c.node(row.data.text("nodeId")), "lastAccessAt" to latest?.data?.get("occurredAt"))
    } },
    "listAccessInvites" to V1Handler { c -> c.inviteList(true) },
    "adminListAccessInvites" to V1Handler { c -> c.inviteList(false) },
    "listCondoInvites" to V1Handler { c -> c.inviteList(false) },
    "createAccessInvite" to V1Handler { c -> c.createInvite() },
    "getAccessInvite" to V1Handler { c -> V1Response(c.invite(c.record("access_invite", "inviteId"))) },
    "updateAccessInvite" to V1Handler { c ->
        val row = c.record("access_invite", "inviteId")
        if (c.inviteStatus(row) !in setOf("scheduled", "active")) c.fail(409, "INVITE_NOT_EDITABLE", "Convite indisponível para edição")
        val data = row.data.merge(c.input)
        c.validateInvite(data)
        V1Response(c.invite(c.change(row, data)))
    },
    "revokeAccessInvite" to V1Handler { c -> c.revokeInvite() },
    "adminRevokeAccessInvite" to V1Handler { c -> c.revokeInvite() },
    "getAccessCredential" to V1Handler { c -> c.accessCredential(c.record("access_invite", "inviteId")) },
)
private fun V1Context.inviteList(own: Boolean) = listResponse("access_invite", own) { row ->
    if (query["status"]?.let { it != inviteStatus(row) } == true) JsonNull else invite(row)
}
