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

