package com.community.api.v1.platform

import com.community.api.core.*
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.ZoneId

fun platformHandlers(): Map<String, V1Handler> = condominiumHandlers() + structureHandlers() +
    membershipAdminHandlers() + staffAdminHandlers() + organizationHandlers() + brandAdminHandlers()

private fun condominiumHandlers(): Map<String, V1Handler> = mapOf(
    "createCondominium" to V1Handler { it.createCondominium() },
    "listManagedCondominiums" to V1Handler { c -> V1Response(c.page("condominium",
        predicate = { c.managesCondominium(it.id) }, transform = { c.condominiumView(it) })) },
    "adminGetCondominium" to V1Handler { c ->
        val record = c.store.get("condominium", c.condominiumId())
        V1Response(c.condominiumView(record), headers = mapOf("ETag" to "\"${record.version}\""))
    },
    "adminUpdateCondominium" to V1Handler { it.updateCondominium() },
    "updateModules" to V1Handler { it.updateCondominiumModules() },
)

internal fun V1Context.managesCondominium(id: String): Boolean {
    if (brandAdministrator()) return true
    val assignments = store.list("staff_assignment", ownerId = userId).filter { it.data.string("status") == "active" }
    return assignments.any { assignment -> assignment.locationId == id ||
        assignment.data.string("scope") == "organization" && store.list("organization_condominium", id,
            filters = mapOf("organizationId" to assignment.data.string("organizationId").orEmpty(), "status" to "active")).isNotEmpty()
    }
}

internal fun V1Context.condominiumView(record: Record): JsonObject {
    val roles = store.list("staff_assignment", ownerId = userId).filter { it.data.string("status") == "active" &&
        (it.locationId == null || it.locationId == record.id) }
    val role = roles.maxByOrNull { roleRanks[it.data.string("role")] ?: 0 }?.data?.string("role") ?: "resident"
    return project("CondominiumAdmin", record.document().plusFields("myRole" to role,
        "activeMemberships" to store.list("membership", record.id, filters = mapOf("status" to "active")).size,
        "nodesCount" to store.list("node", record.id).count { it.data.bool("active", true) }))
}

