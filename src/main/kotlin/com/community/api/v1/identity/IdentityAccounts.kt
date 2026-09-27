package com.community.api.v1.identity

import com.community.api.core.*
import com.community.api.identity.*
import com.community.api.v1.*
import kotlinx.serialization.json.*

fun indexIdentityAccount(tx: Tx, account: Record, cpf: String? = null, phone: String? = null) {
    val email = account.decode<Account>().email.takeIf { it.isNotBlank() }
    for ((type, value) in listOf("email" to email, "cpf" to cpf, "phone" to phone)) {
        if (value == null) continue
        val id = identityIndexId(account.tenantId, type, value)
        val existing = tx.get("v1_identifier", id, account.tenantId)
        if (existing != null && existing.ownerId != account.id) {
            identityFailure(409, "IDENTIFIER_ALREADY_USED", "O identificador já pertence a outra conta")
        }
        if (existing == null) tx.create("v1_identifier", account.tenantId, ownerId = account.id,
            data = obj("type" to type), id = id)
    }
}

internal fun identityIndexId(tenantId: String, type: String, value: String): String =
    digest("$tenantId:$type:$value")

internal fun V1Context.findIdentity(type: String, value: String): Record? {
    val index = tx.get("v1_identifier", identityIndexId(tenantId, type, value), tenantId) ?: return null
    return index.ownerId?.let { tx.get("account", it, tenantId) }
}

internal fun V1Context.account(): Record =
    tx.get("account", userId, tenantId) ?: fail(401, "SESSION_REVOKED", "A conta não está disponível")

internal fun V1Context.profileData(user: Record): JsonObject =
    store.find("profile", user.id)?.data ?: obj("phone" to null, "phoneVerifiedAt" to null,
        "emailVerifiedAt" to null, "preferences" to obj("inApp" to true, "sms" to false, "whatsapp" to false),
        "privacy" to obj("marketingConsent" to false, "cameraAccessConsent" to false,
            "shareContactWithNeighbors" to false, "analytics" to false,
            "termsVersion" to "", "consentUpdatedAt" to user.createdAt))

internal fun V1Context.saveProfile(user: Record, data: JsonObject): Record {
