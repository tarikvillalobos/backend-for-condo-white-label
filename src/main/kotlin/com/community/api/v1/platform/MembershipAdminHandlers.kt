package com.community.api.v1.platform

import com.community.api.core.*
import com.community.api.identity.*
import com.community.api.v1.*
import kotlinx.serialization.json.*

internal fun membershipAdminHandlers(): Map<String, V1Handler> = mapOf(
    "adminListMemberships" to V1Handler { c -> V1Response(c.page("membership",
        filters = c.query.filterKeys { it in setOf("role", "status") }, predicate = { record ->
            val subtree = c.query["nodeId"]?.let { c.nodeDescendants(it) }
            val text = c.query["q"]
            (subtree == null || record.data.string("nodeId") in subtree) &&
                (text == null || c.tx.get("account", record.ownerId!!, c.tenantId)?.data?.string("name")?.contains(text, true) == true)
        }, transform = { c.membershipAdminView(it) })) },
    "adminGetMembership" to V1Handler { c ->
        val record = c.store.get("membership", c.pathId("membershipId"), c.condominiumId())
        V1Response(c.membershipAdminView(record), headers = mapOf("ETag" to "\"${record.version}\""))
    },
    "adminCreateMembership" to V1Handler { it.createMembership() },
    "adminUpdateMembership" to V1Handler { it.updateMembership() },
    "adminImportMemberships" to V1Handler { it.importMemberships() },
) + invitationAdminHandlers()

internal fun V1Context.createMembership(): V1Response {
    val nodeId = required("nodeId")
    checkAddressableNode(nodeId)
    val permissions = checkedPermissions(input.arr("permissions"), required("role"))
    val person = registerPerson(input["person"]!!.jsonObject)
    val user = person.account
    if (store.list("membership", condominiumId(), user.id).any { it.data.string("nodeId") == nodeId &&
            it.data.string("role") == required("role") && it.data.string("status") != "ended" }) {
        fail(409, "ALREADY_LINKED", "A pessoa já possui este papel no nó")
    }
    val active = user.decode<Account>().active
    val data = obj("brandId" to brandId, "userId" to user.id, "condominiumId" to condominiumId(), "nodeId" to nodeId,
        "role" to required("role"), "status" to if (active) "active" else "pending", "permissions" to permissions,
        "canManageNode" to input.bool("canManageNode"), "startedAt" to now.toString(), "endedAt" to null)
    val member = store.create("membership", data, condominiumId(), user.id)
    val invite = if (!active) createPlatformInvitation(JsonObject(input["person"]!!.jsonObject + obj("nodeId" to nodeId,
        "role" to required("role"), "expiresInDays" to (input["invitationExpiresInDays"] ?: JsonPrimitive(7)),
        "deliver" to input.arr("sendInvitation"))), membershipId = member.id, userId = user.id) else null
    return V1Response(obj("membership" to membershipAdminView(member), "userCreated" to person.created, "invitation" to invite), 201)
}

private fun V1Context.updateMembership(): V1Response {
    val record = store.get("membership", pathId("membershipId"), condominiumId())
    if (record.ownerId == userId) fail(403, "SELF_MEMBERSHIP_CHANGE", "Não é permitido alterar o próprio vínculo")
    input.string("nodeId")?.let { checkAddressableNode(it) }
    val role = input.string("role") ?: record.data.string("role")!!
    val permissions = if ("permissions" in input) checkedPermissions(input.arr("permissions"), role) else null
    val updatedData = JsonObject(record.data + input).plusFields("permissions" to (permissions ?: record.data.arr("permissions")))
    val targetNode = updatedData.string("nodeId")
    if (store.list("membership", condominiumId(), record.ownerId).any { it.id != record.id &&
            it.data.string("nodeId") == targetNode && it.data.string("role") == role && it.data.string("status") != "ended" }) {
        fail(409, "ALREADY_LINKED", "A pessoa já possui este papel no nó")
    }
    val state = input.string("status")
    val updated = platformUpdate(record, updatedData.plusFields("endedAt" to if (state == "ended") now.toString()
        else if (state == "active") null else record.data["endedAt"]))
