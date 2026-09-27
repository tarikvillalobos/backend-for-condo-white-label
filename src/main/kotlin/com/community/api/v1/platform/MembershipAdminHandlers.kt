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
