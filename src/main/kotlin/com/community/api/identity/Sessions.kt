package com.community.api.identity

import com.community.api.core.*
import java.time.Instant
import java.util.UUID

fun Tx.authenticate(token: String): Actor {
    val parts = parseToken(token) ?: throw ApiException(401, "unauthorized", "Authentication required")
    val record = get("session", parts[1], parts[0]) ?: throw ApiException(401, "unauthorized", "Authentication required")
    val session = record.decode<SessionData>()
    val account = record.ownerId?.let { get("account", it, record.tenantId) }
    if (session.revoked || expired(session.accessExpiresAt) || expired(session.expiresAt) ||
        !sameSecret(session.accessHash, digest(token)) || account?.decode<Account>()?.active != true ||
        !tenantAvailable(record.tenantId)) throw ApiException(401, "unauthorized", "Authentication required")
    return Actor(account.id, record.tenantId, record.id)
}

internal fun Tx.issueSession(account: Record, device: String): Tokens {
    val id = UUID.randomUUID().toString()
    val access = "${account.tenantId}.$id.${secretToken()}"
