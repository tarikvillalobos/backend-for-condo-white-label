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
    val refresh = "${account.tenantId}.$id.${secretToken()}"
    val now = Instant.now()
    val data = SessionData(digest(access), digest(refresh), now.plusSeconds(900).toString(),
        now.plusSeconds(30 * 86_400).toString(), device.take(120), verifiedAt = now.toString())
    // Cap concurrent sessions; oldest active sessions are revoked deterministically.
    list("session", account.tenantId, ownerId = account.id).filter { !it.decode<SessionData>().revoked }
        .sortedByDescending { it.createdAt }.drop(19).forEach { update(it, body(it.decode<SessionData>().copy(revoked = true))) }
    create("session", account.tenantId, ownerId = account.id, data = body(data), id = id)
    identityAudit(account.tenantId, account.id, "session.created")
    return Tokens(access, refresh)
}

internal fun Tx.login(request: LoginRequest, host: String): AuthResult<Tokens> {
    val email = request.email.trim().lowercase(java.util.Locale.ROOT).take(254)
    if (!allowAttempt("login", request.tenantId, email, host)) return AuthResult(status = 429, code = "rate_limited")
    val account = findAccount(request.tenantId, email)
    val data = account?.decode<Account>()
    val valid = Passwords.verify(request.password, data?.passwordHash)
    if (!valid || data?.active != true || !tenantAvailable(request.tenantId) ||
        !loginEnabled(request.tenantId, "passwordLogin", true)) return AuthResult()
