package com.community.api.v1.identity

import com.community.api.core.*
import com.community.api.identity.sameSecret
import com.community.api.v1.*
import kotlinx.serialization.json.*

internal fun deviceHandlers(): Map<String, V1Handler> = mapOf(
    "registerPushDevice" to V1Handler { it.registerIdentityDevice() },
    "unregisterPushDevice" to V1Handler { it.unregisterIdentityDevice() },
)

private fun V1Context.installation(): Pair<String, String> {
    val installationId = identityPath("installationId")
    val key = identityHeader("X-Installation-Key")
        ?: fail(400, "INSTALLATION_KEY_REQUIRED", "Informe a prova de posse da instalação")
    if (!key.matches(Regex("[A-Za-z0-9_-]{43}"))) fail(422, "INVALID_INSTALLATION_KEY", "Chave da instalação inválida")
    return installationId to hash(key)
}

private fun V1Context.registerIdentityDevice(): V1Response {
    val (id, keyHash) = installation()
    val ownership = store.find("installation", id)
    if (ownership != null && !sameSecret(ownership.data.string("keyHash").orEmpty(), keyHash)) {
        fail(403, "INSTALLATION_KEY_MISMATCH", "A prova de posse da instalação não confere")
    }
    val platform = identityInput("platform")
    val provider = identityInput("provider")
    if (platform == "android" && provider != "fcm" || platform == "ios" && provider != "apns") {
        fail(422, "INVALID_PUSH_PROVIDER", "O provedor não corresponde à plataforma")
    }
    if (ownership == null) store.create("installation", obj("keyHash" to keyHash), id = id)
    val current = store.find("push_registration", id)
    val token = identityInput("token")
    val sameRegistration = current != null && current.data.string("userId") == userId && current.data.string("status") == "active" &&
        current.data.string("sessionId") == principal!!.sessionId &&
        current.data.string("tokenHash") == hash(token) &&
        current.data.string("appVersion") == input.string("appVersion") &&
        current.data.string("permission") == input.string("permission")
    if (sameRegistration) return V1Response(obj("installationId" to id, "registeredAt" to current!!.data["registeredAt"]))
    val registration = obj("brandId" to brandId, "sessionId" to principal!!.sessionId,
        "userId" to userId, "status" to "active",
        "platform" to platform, "provider" to provider, "tokenEncrypted" to seal(token), "tokenHash" to hash(token),
        "permission" to input["permission"], "appVersion" to input["appVersion"], "registeredAt" to now.toString())
    if (current != null) store.update(current, registration)
    else store.create("push_registration", registration, id = id)
    return V1Response(obj("installationId" to id, "registeredAt" to now.toString()))
}

private fun V1Context.unregisterIdentityDevice(): V1Response {
    val (id, keyHash) = installation()
    val ownership = store.find("installation", id)
    if (ownership != null && !sameSecret(ownership.data.string("keyHash").orEmpty(), keyHash)) {
        fail(403, "INSTALLATION_KEY_MISMATCH", "A prova de posse da instalação não confere")
    }
    val registration = store.find("push_registration", id)
    if (registration != null && registration.data.string("userId") == userId && registration.data.string("sessionId") == principal!!.sessionId) {
        store.update(registration, registration.data.with("status" to "inactive", "tokenEncrypted" to null, "tokenHash" to null))
    }
    return V1Response(status = 204)
}
