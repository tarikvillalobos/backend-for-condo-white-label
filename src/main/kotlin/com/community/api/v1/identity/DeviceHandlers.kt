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

