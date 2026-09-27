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
    "codeLength" to 6, "purpose" to metadata["purpose"],
)

internal fun V1Context.challengeMetadata(id: String, purpose: String, owned: Boolean): Record {
    val record = store.find("challenge", id)
        ?: fail(404, "CHALLENGE_NOT_FOUND", "Desafio não encontrado")
    if (record.data.string("brandId") != brandId || record.data.string("purpose") != purpose ||
        owned && (record.ownerId != userId || record.data.string("sessionId") != principal?.sessionId)) {
        fail(404, "CHALLENGE_NOT_FOUND", "Desafio não encontrado")
    }
    return record
}

internal fun V1Context.verifyIdentityChallenge(id: String, purpose: String, owned: Boolean = false): VerifiedChallenge {
    val metadata = challengeMetadata(id, purpose, owned)
    val challenge = tx.get("auth_challenge", id, tenantId)
        ?: fail(404, "CHALLENGE_NOT_FOUND", "Desafio não encontrado")
    val data = challenge.decode<ChallengeData>()
    if (data.consumed) fail(410, "CHALLENGE_CONSUMED", "Este desafio já foi utilizado")
    if (!Instant.parse(data.expiresAt).isAfter(now)) fail(410, "CHALLENGE_EXPIRED", "Este desafio expirou")
    if (data.attempts >= 5) return VerifiedChallenge(null, rateLimited())
    if (!identityRate("v1-verify", id)) return VerifiedChallenge(null, rateLimited())
    val user = metadata.ownerId?.let { tx.get("account", it, tenantId) }
    val supplied = identityInput("code")
    if (user?.decode<Account>()?.active != true || !sameSecret(data.secretHash, digest("$id:$supplied"))) {
        tx.update(challenge, body(data.copy(attempts = data.attempts + 1)))
        return VerifiedChallenge(null, if (data.attempts + 1 >= 5) rateLimited()
            else identityError(422, "INVALID_CODE", "Código inválido"))
    }
    tx.consumeChallenge(challenge)
    return VerifiedChallenge(user)
}

internal fun V1Context.resendIdentityChallenge(purpose: String, owned: Boolean): V1Response {
    val metadata = challengeMetadata(identityPath("challengeId"), purpose, owned)
    val challenge = tx.get("auth_challenge", metadata.id, tenantId)
        ?: fail(404, "CHALLENGE_NOT_FOUND", "Desafio não encontrado")
    val data = challenge.decode<ChallengeData>()
    if (data.consumed || !Instant.parse(data.expiresAt).isAfter(now)) {
        fail(410, "CHALLENGE_EXPIRED", "Este desafio não está mais disponível")
    }
    if (Instant.parse(metadata.data.string("resendAt")).isAfter(now)) return rateLimited()
    val destination = metadata.data.string("destination")!!
    if (!identityRate("v1-challenge", destination)) return rateLimited()
    requireEmailChannel("email")
    tx.consumeChallenge(challenge)
    val user = metadata.ownerId?.let { tx.get("account", it, tenantId) }
    return V1Response(issueIdentityChallenge(purpose, user, destination,
        metadata.data.string("sessionId"), metadata.data.string("maskedDestination")!!), 202)
}
