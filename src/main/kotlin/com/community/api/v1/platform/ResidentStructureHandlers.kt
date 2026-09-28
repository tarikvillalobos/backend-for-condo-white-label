package com.community.api.v1.platform

import com.community.api.core.*
import com.community.api.identity.revokeSessions
import com.community.api.v1.*
import com.community.api.v1.identity.profileData
import kotlinx.serialization.json.*

internal fun residentStructureHandlers(): Map<String, V1Handler> = mapOf(
    "getCondominium" to V1Handler { c ->
        val condo = c.store.get("condominium", c.condominiumId())
        V1Response(c.project("CondominiumInfo", condo.document().plusFields("modules" to modulesView(c))))
    },
    "getStructure" to V1Handler { c ->
        val node = c.residentNode(c.unitId ?: c.fail(404, "UNIT_NOT_FOUND", "Vínculo sem unidade"))
        V1Response(structureNodeView(c, node, c.query["depth"]?.toIntOrNull() ?: 2))
    },
    "getStructureNode" to V1Handler { c -> V1Response(structureNodeView(c, c.residentNode(c.pathId("nodeId")),
        c.query["depth"]?.toIntOrNull() ?: 1)) },
    "searchStructure" to V1Handler { c ->
        val visible = c.nodeDescendants(c.unitId ?: c.fail(404, "UNIT_NOT_FOUND", "Vínculo sem unidade"))
        V1Response(c.page("node", predicate = { node -> node.id in visible && node.data.bool("active", true) &&
            (c.query["q"] == null || node.data.string("label")?.contains(c.query["q"]!!, true) == true) &&
            (c.query["type"] == null || node.data.string("typeCode") == c.query["type"]) },
            transform = { structureNodeView(c, it) }))
    },
    "getUnit" to V1Handler { it.residentUnit() },
    "inviteUnitResident" to V1Handler { it.inviteResident() },
    "removeUnitResident" to V1Handler { it.removeResident() },
)

private fun V1Context.residentNode(id: String): Record {
    val scope = unitId ?: fail(404, "UNIT_NOT_FOUND", "Vínculo sem unidade")
    val node = store.get("node", id, condominiumId())
    if (!node.data.bool("active", true) || node.id !in nodeDescendants(scope)) fail(404, "NODE_NOT_FOUND", "Nó não encontrado")
    return node
}

private fun V1Context.residentUnit(): V1Response {
    val node = residentNode(unitId ?: fail(404, "UNIT_NOT_FOUND", "Vínculo sem unidade"))
    val visible = nodeDescendants(node.id)
    val residents = store.list("membership", condominiumId(), filters = mapOf("status" to "active"))
        .filter { it.data.string("nodeId") in visible }.map { membership ->
            val account = tx.get("account", membership.ownerId!!, tenantId)!!
            val profile = profileData(account)
            val sharedPhone = (profile["privacy"] as? JsonObject)?.bool("shareContactWithNeighbors") == true
            obj("id" to membership.id, "name" to account.data["name"], "role" to membership.data["role"],
                "isSelf" to (membership.ownerId == userId), "node" to nodeRef(this, membership.data.string("nodeId")!!),
                "phone" to if (sharedPhone) profile["phone"] else null, "createdAt" to membership.createdAt)
        }
    return V1Response(obj("id" to node.id, "label" to node.data["label"], "condominiumId" to condominiumId(),
        "blockLabel" to nodePathView(this, node.id).map { it.jsonObject }.firstOrNull { it.string("type") in setOf("tower", "block") }?.get("label"),
        "node" to structureNodeView(this, node), "residents" to residents,
        "canManageResidents" to ("unit.manage" in principal!!.permissions || "*" in principal.permissions)))
}

private fun V1Context.inviteResident(): V1Response {
    requirePermission("unit.manage")
    val node = residentNode(input.string("nodeId") ?: unitId!!)
    val email = input.string("email")
    if (email == null) fail(501, "CHANNEL_UNAVAILABLE", "Informe um e-mail; o provedor SMS não está configurado")
    val invitation = createPlatformInvitation(input.plusFields("nodeId" to node.id, "expiresInDays" to 7, "deliver" to listOf("email")))
    return V1Response(JsonObject(invitation - "code"), 201)
}

private fun V1Context.removeResident(): V1Response {
    requirePermission("unit.manage")
    val target = store.get("membership", pathId("residentId"), condominiumId())
    residentNode(target.data.string("nodeId")!!)
    if (target.ownerId == userId || target.data.string("role") == "owner" || target.data.bool("canManageNode")) {
        fail(403, "RESPONSIBLE_RESIDENT_PROTECTED", "O responsável não pode ser removido por esta operação")
    }
    store.update(target, target.data.plusFields("status" to "ended", "endedAt" to now.toString()))
    store.list("invitation", condominiumId(), filters = mapOf("membershipId" to target.id, "status" to "pending"))
        .forEach { store.update(it, it.data.plusFields("status" to "revoked")) }
    store.list("access_invite", condominiumId(), filters = mapOf("membershipId" to target.id))
        .forEach { store.update(it, it.data.plusFields("status" to "revoked", "credentialStatus" to "revoked")) }
    tx.revokeSessions(tenantId, target.ownerId!!)
    return V1Response(status = 204)
}
