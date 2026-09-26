package com.community.api.identity

import com.community.api.core.*
import java.time.Instant
import java.util.UUID

internal data class IssuedChallenge(val record: Record, val token: String, val expiresAt: String)

internal fun Tx.issueChallenge(account: Record, type: String, lifetime: Long, newEmail: String? = null, otp: String? = null): IssuedChallenge {
    list("auth_challenge", account.tenantId, ownerId = account.id).filter {
        val data = it.decode<ChallengeData>()
        data.type == type && !data.consumed
    }.forEach { consumeChallenge(it) }
    val id = UUID.randomUUID().toString()
    val credential = otp ?: "${account.tenantId}.$id.${secretToken()}"
    val expiresAt = Instant.now().plusSeconds(lifetime).toString()
    val data = ChallengeData(type, digest("$id:$credential"), expiresAt, newEmail = newEmail)
    val record = create("auth_challenge", account.tenantId, ownerId = account.id, data = body(data), id = id)
    return IssuedChallenge(record, credential, expiresAt)
}

internal fun Tx.consumeChallenge(record: Record) {
    update(record, body(record.decode<ChallengeData>().copy(consumed = true)))
    list("auth_delivery", record.tenantId, ownerId = record.id).forEach { delete(it) }
}

internal fun Tx.queueDelivery(account: Record, issued: IssuedChallenge, destination: String? = null) {
    val challenge = issued.record.decode<ChallengeData>()
    val delivery = AuthDelivery(challenge.type, destination ?: account.decode<Account>().email, issued.token, issued.expiresAt)
    create("auth_delivery", account.tenantId, ownerId = issued.record.id, data = body(delivery))
}

internal fun Tx.matchChallenge(token: String, type: String): Record? {
    val parts = parseToken(token) ?: return null
    val record = get("auth_challenge", parts[1], parts[0]) ?: return null
    val challenge = record.decode<ChallengeData>()
    if (challenge.type != type || challenge.consumed || expired(challenge.expiresAt) ||
        !sameSecret(challenge.secretHash, digest("${record.id}:$token")) || !tenantAvailable(record.tenantId)) return null
    return record
}
