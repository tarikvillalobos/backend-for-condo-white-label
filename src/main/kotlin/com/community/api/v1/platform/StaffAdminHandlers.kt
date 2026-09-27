package com.community.api.v1.platform

import com.community.api.core.*
import com.community.api.identity.revokeSessions
import com.community.api.v1.*
import kotlinx.serialization.json.*

internal fun staffAdminHandlers(): Map<String, V1Handler> = mapOf(
    "listStaff" to V1Handler { c -> V1Response(obj("items" to c.store.list("staff_assignment", c.condominiumId())
        .map { c.assignmentView(it) })) },
    "createStaffAssignment" to V1Handler { c -> V1Response(c.assignmentView(c.createPlatformStaff()), 201) },
    "updateStaffAssignment" to V1Handler { it.updatePlatformStaff() },
    "listRoles" to V1Handler { c -> V1Response(obj("items" to (roleRanks.keys + setOf("resident", "owner", "tenant", "dependent", "locker_user"))
        .map { c.roleView(it) })) },
    "updateRolePermissions" to V1Handler { it.updatePlatformRole() },
)

internal fun V1Context.assignmentView(record: Record): JsonObject = staffAssignmentsView(this, record.ownerId!!)
    .first { it.jsonObject.string("id") == record.id }.jsonObject

internal fun V1Context.createPlatformStaff(organizationId: String? = null): Record {
    val role = required("role")
    ensureRoleAuthority(role)
    val userId = input.string("userId")
    val invite = input["invite"] as? JsonObject
    if ((userId == null) == (invite == null)) fail(422, "INVALID_STAFF_IDENTITY", "Informe userId ou invite")
    val account = if (userId != null) tx.get("account", userId, tenantId)
        ?: fail(422, "USER_NOT_FOUND", "Conta não encontrada") else registerPerson(invite!!).account
    val condoId = if (organizationId != null) input.string("condominiumId") else condominiumId()
    if (organizationId != null && condoId != null && store.list("organization_condominium", condoId,
            filters = mapOf("organizationId" to organizationId, "status" to "active")).isEmpty()) {
        fail(422, "CONDOMINIUM_NOT_LINKED", "O condomínio não pertence ao escopo da organização")
    }
    val customId = input.string("customRoleId")
    val custom = customId?.let { store.get("custom_role", it) }
    if (custom != null && (custom.data.string("organizationId") != organizationId || custom.data.string("baseRole") != role)) {
        fail(422, "INVALID_CUSTOM_ROLE", "O papel customizado não corresponde ao escopo ou papel base")
    }
    val requested = custom?.data?.arr("permissions") ?: input.arr("permissions").takeIf { it.isNotEmpty() }
        ?: JsonArray(platformRolePermissions(this, role).map(::JsonPrimitive))
    val permissions = checkedPermissions(requested, role)
    if (store.list("staff_assignment", ownerId = account.id).any { it.locationId == condoId &&
            it.data.string("organizationId") == organizationId && it.data.string("status") == "active" }) {
        fail(409, "STAFF_ALREADY_ASSIGNED", "A pessoa já possui uma atribuição ativa neste escopo")
    }
    val record = store.create("staff_assignment", obj("userId" to account.id, "brandId" to brandId,
        "role" to role, "condominiumId" to condoId, "organizationId" to organizationId,
        "scope" to if (condoId == null) "organization" else "condominium", "status" to "active",
        "permissions" to permissions, "permissionsCustomized" to (custom != null || input.arr("permissions").isNotEmpty()),
        "customRoleId" to customId, "customRole" to custom?.let { obj("id" to it.id, "code" to it.data["code"], "name" to it.data["name"]) },
        "mfaRequired" to input.bool("mfaRequired", true), "startedAt" to now.toString(), "endedAt" to null), condoId, account.id)
    if (invite != null && !account.data.bool("active")) {
        if (condoId == null) issueOrganizationStaffInvitation(account, record, organizationId!!)
        else withInput(input, condoId).createPlatformInvitation(JsonObject(invite + obj("nodeId" to withInput(input, condoId).rootNode().id,
            "role" to role, "expiresInDays" to 7, "deliver" to listOf("email"))), userId = account.id, assignmentId = record.id)
    }
    return record
}

private fun V1Context.updatePlatformStaff(): V1Response {
