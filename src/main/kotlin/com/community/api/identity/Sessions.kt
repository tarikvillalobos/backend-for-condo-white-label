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
    return AuthResult(issueSession(account ?: return AuthResult(), request.device))
}

internal fun Tx.refresh(token: String, host: String): AuthResult<Tokens> {
    val parts = parseToken(token) ?: return AuthResult()
    if (!allowAttempt("refresh", parts[0], parts[1], host)) return AuthResult(status = 429, code = "rate_limited")
    val record = get("session", parts[1], parts[0]) ?: return AuthResult()
    val session = record.decode<SessionData>()
    val suppliedHash = digest(token)
    if (session.usedRefreshHashes.any { sameSecret(it, suppliedHash) }) {
        update(record, body(session.copy(revoked = true)))
        identityAudit(record.tenantId, record.ownerId, "session.refresh_replay")
        return AuthResult()
    }
    val account = record.ownerId?.let { get("account", it, record.tenantId) }
    if (session.revoked || expired(session.expiresAt) || !sameSecret(session.refreshHash, suppliedHash) ||
        account?.decode<Account>()?.active != true || !tenantAvailable(record.tenantId)) return AuthResult()
    if (session.usedRefreshHashes.size >= 1024) {
        update(record, body(session.copy(revoked = true)))
        return AuthResult()
    }
    val access = "${record.tenantId}.${record.id}.${secretToken()}"
    val refresh = "${record.tenantId}.${record.id}.${secretToken()}"
    update(record, body(session.copy(accessHash = digest(access), refreshHash = digest(refresh),
        accessExpiresAt = Instant.now().plusSeconds(900).toString(), usedRefreshHashes = session.usedRefreshHashes + suppliedHash)))
    return AuthResult(Tokens(access, refresh))
}
