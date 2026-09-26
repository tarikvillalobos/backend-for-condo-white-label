package com.community.api.identity

import com.community.api.core.*

internal fun Tx.changePassword(actor: Actor, request: PasswordRequest, host: String): AuthResult<Accepted> {
    if (!allowAttempt("change-password", actor.tenantId, actor.userId, host)) return AuthResult(status = 429, code = "rate_limited")
    val account = get("account", actor.userId, actor.tenantId) ?: return AuthResult()
    val data = account.decode<Account>()
    if (!Passwords.verify(request.currentPassword, data.passwordHash)) return AuthResult()
    update(account, body(data.copy(passwordHash = Passwords.hash(request.newPassword))))
    revokeAccountCredentials(actor.tenantId, actor.userId)
    identityAudit(actor.tenantId, actor.userId, "account.password_changed")
    return AuthResult(Accepted())
}

internal fun Tx.requestContactChange(actor: Actor, request: ContactRequest, host: String): AuthResult<Accepted> {
    val email = normalizedEmail(request.email)
    if (!allowAttempt("change-contact", actor.tenantId, actor.userId, host)) return AuthResult(status = 429, code = "rate_limited")
    val account = get("account", actor.userId, actor.tenantId) ?: return AuthResult()
    if (!Passwords.verify(request.password, account.decode<Account>().passwordHash)) return AuthResult()
    if (findAccount(actor.tenantId, email) != null) return AuthResult(Accepted())
    val challenge = issueChallenge(account, "contact", 1800, newEmail = email)
    queueDelivery(account, challenge, email)
    return AuthResult(Accepted())
}

internal fun Tx.confirmContactChange(actor: Actor, token: String): AuthResult<Accepted> {
    val challenge = matchChallenge(token, "contact") ?: return AuthResult()
    if (challenge.tenantId != actor.tenantId || challenge.ownerId != actor.userId) return AuthResult()
    val email = challenge.decode<ChallengeData>().newEmail ?: return AuthResult()
    if (findAccount(actor.tenantId, email) != null) return AuthResult()
    val account = get("account", actor.userId, actor.tenantId) ?: return AuthResult()
    update(account, body(account.decode<Account>().copy(email = email)))
    consumeChallenge(challenge)
    revokeAccountCredentials(actor.tenantId, actor.userId)
    identityAudit(actor.tenantId, actor.userId, "account.contact_changed")
    return AuthResult(Accepted())
}
