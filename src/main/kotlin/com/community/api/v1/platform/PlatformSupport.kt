package com.community.api.v1.platform

import com.community.api.core.*
import com.community.api.v1.*
import kotlinx.serialization.json.*

internal fun JsonObject.plusFields(vararg fields: Pair<String, Any?>) = JsonObject(this + obj(*fields))
internal fun JsonObject.arr(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())
internal fun JsonObject.bool(key: String, default: Boolean = false): Boolean = get(key)?.jsonPrimitive?.booleanOrNull ?: default
internal fun V1Context.required(key: String) = input.string(key) ?: fail(422, "VALIDATION_ERROR", "$key é obrigatório")
internal fun V1Context.pathId(key: String) = path[key] ?: fail(400, "VALIDATION_ERROR", "$key é obrigatório")
internal fun V1Context.condominiumId() = locationId ?: pathId("condominiumId")
internal fun V1Context.withInput(body: JsonObject, location: String? = locationId) = V1Context(tx, operationId,
    tenantId, brandId, requestId, body, path, query, headers, principal, location, membership, now)
internal fun V1Context.platformResult(schema: String, record: Record, status: Int = 200) =
    V1Response(project(schema, record.document()), status, mapOf("ETag" to "\"${record.version}\""))
internal fun V1Context.platformUpdate(record: Record, data: JsonObject): Record {
    if (header("If-Match") != null) requireVersion(record)
    return store.update(record, data)
}
internal fun V1Context.brandAdministrator(): Boolean = store.list("staff_assignment", ownerId = userId)
    .any { it.data.string("role") == "brand_admin" && it.data.string("status") == "active" }
internal fun V1Context.requireBrandAdministrator() {
    if (!brandAdministrator()) fail(403, "BRAND_ADMIN_REQUIRED", "Esta operação exige administrador da marca")
}

internal val roleRanks = mapOf("brand_admin" to 7, "org_admin" to 6, "property_manager" to 5,
    "condo_admin" to 4, "manager" to 3, "porter" to 2, "support" to 1)

fun platformRolePermissions(c: V1Context, role: String): Set<String> {
    val customized = c.store.find("role", role)?.data?.arr("permissions")
    if (customized != null) return customized.map { it.jsonPrimitive.content }.toSet()
    val catalog = Contract.document["x-permission-catalog"]!!.jsonArray.map { it.jsonObject }
    val residents = catalog.filter { it.string("audience") == "resident" }.map { it.string("code")!! }.toSet()
    val staff = catalog.filter { it.string("audience") in setOf("resident", "staff") }.map { it.string("code")!! }.toSet()
    return when (role) {
        "brand_admin" -> catalog.map { it.string("code")!! }.toSet()
        "org_admin" -> staff + catalog.filter { it.string("audience") == "organization" }.map { it.string("code")!! } - "roles.manage"
        "property_manager" -> staff - "roles.manage"
        "condo_admin" -> staff - setOf("roles.manage", "condominiums.create")
