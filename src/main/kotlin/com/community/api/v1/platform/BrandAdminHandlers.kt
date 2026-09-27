package com.community.api.v1.platform

import com.community.api.core.*
import com.community.api.identity.*
import com.community.api.v1.*
import com.community.api.v1.identity.*
import kotlinx.serialization.json.*

internal fun brandAdminHandlers(): Map<String, V1Handler> = mapOf(
    "getBrandSettings" to V1Handler { c -> c.platformResult("BrandSettings", c.store.get("brand", c.brandId)) },
    "updateBrandSettings" to V1Handler { it.updatePlatformBrand() },
    "listPermissionCatalog" to V1Handler { V1Response(obj("items" to Contract.document["x-permission-catalog"])) },
    "listAccounts" to V1Handler { c ->
        c.requireBrandAdministrator()
        V1Response(c.page("profile", predicate = { record ->
            val user = c.tx.get("account", record.ownerId!!, c.tenantId)
            val search = c.query["q"]
            user != null && (search == null || user.data.string("name")?.contains(search, true) == true ||
                user.data.string("email")?.contains(search, true) == true)
        }, transform = { c.accountSummary(c.tx.get("account", it.ownerId!!, c.tenantId)!!) }))
    },
    "updateAccountState" to V1Handler { it.updatePlatformAccount() },
)

private fun V1Context.updatePlatformBrand(): V1Response {
    requireBrandAdministrator()
    val brand = store.get("brand", brandId)
    if (input.arr("authMethods").isEmpty() && "authMethods" in input) fail(422, "AUTH_METHOD_REQUIRED", "Mantenha pelo menos um método de acesso")
    input.string("logoFileKey")?.let { store.get("upload", it) }
    val updated = platformUpdate(brand, JsonObject(brand.data + input))
    (input["modules"] as? JsonObject)?.let { modules ->
        val disabled = modules.filterValues { it == JsonPrimitive(false) }
        if (disabled.isNotEmpty()) store.list("condominium").forEach { condo ->
            store.update(condo, condo.data.plusFields("modules" to JsonObject(condo.data["modules"]!!.jsonObject + disabled)))
        }
    }
    return platformResult("BrandSettings", updated)
}

private fun V1Context.accountSummary(user: Record): JsonObject {
