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
