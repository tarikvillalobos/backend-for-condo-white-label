package com.community.api.v1.platform

import com.community.api.v1.*
import kotlinx.serialization.json.*

internal fun customRoleHandlers(): Map<String, V1Handler> = mapOf(
    "listCustomRoles" to V1Handler { c -> V1Response(obj("items" to c.store.list("custom_role",
        filters = mapOf("organizationId" to c.pathId("organizationId"))).map { c.project("CustomRole", it.document()) })) },
    "createCustomRole" to V1Handler { it.saveCustomRole(false) },
    "updateCustomRole" to V1Handler { it.saveCustomRole(true) },
)

private fun V1Context.saveCustomRole(update: Boolean): V1Response {
    val orgId = pathId("organizationId")
    store.get("organization", orgId)
    val role = required("baseRole")
    ensureRoleAuthority(role)
    val permissions = checkedPermissions(input.arr("permissions"), role)
    val existing = if (update) store.get("custom_role", pathId("roleId")) else null
    if (existing != null && existing.data.string("organizationId") != orgId) fail(404, "ROLE_NOT_FOUND", "Papel não encontrado")
    if (store.list("custom_role", filters = mapOf("organizationId" to orgId, "code" to required("code"))).any { it.id != existing?.id }) {
        fail(409, "ROLE_CODE_EXISTS", "Já existe um papel com esse código")
    }
    val data = input.plusFields("organizationId" to orgId, "permissions" to permissions)
    val record = if (existing == null) store.create("custom_role", data) else platformUpdate(existing, data)
    store.list("staff_assignment", filters = mapOf("customRoleId" to record.id)).forEach {
        store.update(it, it.data.plusFields("role" to role, "permissions" to permissions,
            "customRole" to obj("id" to record.id, "code" to record.data["code"], "name" to record.data["name"])))
    }
    return platformResult("CustomRole", record, if (update) 200 else 201)
}
