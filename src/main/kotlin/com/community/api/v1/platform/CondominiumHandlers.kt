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

private fun V1Context.createCondominium(): V1Response {
    checkedTimeZone(required("timeZone"))
    checkedModules(input["modules"]!!.jsonObject)
    val defaults = obj("active" to true, "support" to emptySupport(), "rulesUrl" to null,
        "settings" to obj(), "petRules" to obj("vaccinationRequired" to false, "maxPetsPerNode" to null, "allowedSpecies" to emptyList<String>()))
    val record = store.create("condominium", JsonObject(defaults + (input - "propertyManagerUserId")))
    val types = seedDefaultNodeTypes(record.id)
    val rootType = types.first { it.data.string("code") == "root" }
    val root = store.create("node", obj("typeId" to rootType.id, "typeCode" to "root", "label" to required("name"),
        "parentId" to null, "code" to null, "attributes" to obj(), "receivesAsEntity" to false,
        "active" to true, "sortOrder" to 0), record.id)
    val updated = store.update(record, record.data.plusFields("rootNodeId" to root.id))
    val manager = input.string("propertyManagerUserId") ?: if (!brandAdministrator()) userId else null
    manager?.let {
        if (tx.get("account", it, tenantId) == null) fail(422, "USER_NOT_FOUND", "Administradora não encontrada")
        store.create("staff_assignment", obj("userId" to it, "brandId" to brandId, "role" to "property_manager",
            "scope" to "condominium", "condominiumId" to record.id, "organizationId" to null,
            "permissions" to platformRolePermissions(this, "property_manager"), "status" to "active",
            "mfaRequired" to true, "startedAt" to now.toString(), "endedAt" to null), record.id, it)
    }
    return V1Response(obj("condominium" to condominiumView(updated), "rootNodeId" to root.id,
        "nodeTypes" to types.map { project("NodeType", it.document()) }), 201)
}

private fun V1Context.updateCondominium(): V1Response {
    val record = store.get("condominium", condominiumId())
    input.string("timeZone")?.let(::checkedTimeZone)
    (input["modules"] as? JsonObject)?.let { checkedModules(it) }
    val updated = platformUpdate(record, JsonObject(record.data + input))
    return V1Response(condominiumView(updated), headers = mapOf("ETag" to "\"${updated.version}\""))
}

private fun V1Context.updateCondominiumModules(): V1Response {
    checkedModules(input)
    val record = store.get("condominium", condominiumId())
    val modules = JsonObject(record.data["modules"]!!.jsonObject + input)
    platformUpdate(record, record.data.plusFields("modules" to modules))
    return V1Response(modules)
}

internal fun V1Context.checkedModules(modules: JsonObject) {
    val brandModules = store.get("brand", brandId).data["modules"]!!.jsonObject
    if (modules.any { (key, value) -> value == JsonPrimitive(true) && brandModules[key] != JsonPrimitive(true) }) {
        fail(403, "MODULE_DISABLED", "O módulo está desabilitado na marca")
    }
}

private fun checkedTimeZone(value: String) {
    if (runCatching { ZoneId.of(value) }.isFailure) throw ApiException(422, "INVALID_TIME_ZONE", "Fuso horário IANA inválido")
}

internal fun emptySupport(): JsonObject = obj("phone" to null, "whatsapp" to null, "email" to null,
    "hours" to null, "privacyPolicyUrl" to null, "termsUrl" to null)
