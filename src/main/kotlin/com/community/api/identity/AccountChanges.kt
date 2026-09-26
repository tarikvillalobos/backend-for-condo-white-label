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
