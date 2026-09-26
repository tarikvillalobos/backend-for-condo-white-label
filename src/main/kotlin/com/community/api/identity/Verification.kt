package com.community.api.identity

import com.community.api.core.*
import java.time.Instant

fun Tx.requireRecentAuthentication(actor: Actor) {
    val session = get("session", actor.sessionId, actor.tenantId)?.decode<SessionData>()
    val verified = session?.verifiedAt?.let(Instant::parse)
    if (session == null || session.revoked || verified == null || verified.isBefore(Instant.now().minusSeconds(600))) {
        throw ApiException(403, "verification_required", "Verify your password before this action")
    }
}

internal fun Tx.verifyIdentity(actor: Actor, password: String, host: String): AuthResult<Accepted> {
    if (!allowAttempt("verify", actor.tenantId, actor.userId, host)) return AuthResult(status = 429, code = "rate_limited")
    val account = get("account", actor.userId, actor.tenantId) ?: return AuthResult()
    if (!Passwords.verify(password, account.decode<Account>().passwordHash)) return AuthResult()
    val session = get("session", actor.sessionId, actor.tenantId) ?: return AuthResult()
    update(session, body(session.decode<SessionData>().copy(verifiedAt = Instant.now().toString())))
    return AuthResult(Accepted())
}
