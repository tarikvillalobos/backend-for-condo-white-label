package com.community.api.v1.identity

import com.community.api.core.*
import com.community.api.identity.*
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

internal data class VerifiedChallenge(val account: Record?, val failure: V1Response? = null)

internal fun V1Context.requireEmailChannel(channel: String) {
    if (channel != "email") fail(501, "CHANNEL_UNAVAILABLE", "O provedor deste canal não está configurado")
    if (MailConfig.fromEnvironment() == null) {
        fail(503, "CHANNEL_UNAVAILABLE", "O serviço de e-mail não está configurado")
    }
}

internal fun V1Context.issueIdentityChallenge(
    purpose: String, user: Record?, destination: String, sessionId: String? = null,
    displayDestination: String = maskedContact(destination, "email"),
): JsonObject {
    val id = UUID.randomUUID().toString()
    val otp = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
    val expiresAt = now.plusSeconds(300).toString()
    val type = when (purpose) { "password_recovery" -> "recovery"; "contact_change" -> "contact"; else -> "otp" }
    val challenge = tx.create("auth_challenge", tenantId, ownerId = user?.id, id = id,
        data = body(ChallengeData(type, digest("$id:$otp"), expiresAt)))
    val metadata = obj("brandId" to brandId, "purpose" to purpose, "channel" to "email",
        "destination" to destination, "maskedDestination" to displayDestination,
        "sessionId" to sessionId, "expiresAt" to expiresAt, "resendAt" to now.plusSeconds(30).toString())
    store.create("challenge", metadata, ownerId = user?.id, id = id)
    if (user != null) tx.queueDelivery(user, IssuedChallenge(challenge, "sealed:" + seal(otp), expiresAt), destination)
    return challengeView(id, metadata)
}

internal fun challengeView(id: String, metadata: JsonObject): JsonObject = obj(
    "id" to id, "expiresAt" to metadata["expiresAt"], "resendAt" to metadata["resendAt"],
    "channel" to metadata["channel"], "maskedDestination" to metadata["maskedDestination"],
