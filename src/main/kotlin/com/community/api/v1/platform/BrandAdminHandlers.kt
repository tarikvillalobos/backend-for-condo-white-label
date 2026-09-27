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
    val account = user.decode<Account>()
    val profile = profileData(user)
    val status = when {
        profile.string("accountStatus") == "blocked" -> "blocked"
        account.active -> "active"
        account.passwordHash.isEmpty() -> "pending"
        else -> "blocked"
    }
    return obj("id" to user.id, "name" to account.name,
        "maskedEmail" to account.email.takeIf { it.isNotEmpty() }?.let { maskedContact(it, "email") },
        "maskedPhone" to profile.string("phone")?.let { maskedContact(it, "sms") }, "status" to status,
        "contextsCount" to (store.list("membership", ownerId = user.id, filters = mapOf("status" to "active")).size +
            store.list("staff_assignment", ownerId = user.id, filters = mapOf("status" to "active")).size),
        "lastSeenAt" to store.list("session", ownerId = user.id).mapNotNull { it.data.string("lastSeenAt") }.maxOrNull(), "createdAt" to user.createdAt)
}

private fun V1Context.updatePlatformAccount(): V1Response {
    requireBrandAdministrator()
    val id = pathId("userId")
    if (id == userId) fail(403, "SELF_ACCOUNT_CHANGE", "Não é permitido bloquear a própria conta")
    store.get("profile", id)
    val user = tx.get("account", id, tenantId) ?: fail(404, "USER_NOT_FOUND", "Conta não encontrada")
    if (!user.decode<Account>().active && user.decode<Account>().passwordHash.isEmpty()) {
        fail(409, "ACCOUNT_PENDING", "A conta precisa concluir o primeiro acesso")
    }
    saveProfile(user, profileData(user).with("accountStatus" to required("status")))
    if (required("status") == "blocked") store.list("session", ownerId = id).forEach { revokeIdentitySession(it.id) }
    return V1Response(accountSummary(user))
}
