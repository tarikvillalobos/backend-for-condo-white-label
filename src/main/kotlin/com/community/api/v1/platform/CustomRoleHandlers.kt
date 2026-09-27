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
