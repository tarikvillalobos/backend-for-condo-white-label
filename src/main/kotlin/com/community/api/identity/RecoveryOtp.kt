package com.community.api.identity

import com.community.api.core.*
import java.security.SecureRandom
import java.util.Locale

internal fun Tx.requestRecovery(request: EmailRequest, host: String, otp: Boolean): AuthResult<Accepted> {
    val type = if (otp) "otp" else "recovery"
    val email = request.email.trim().lowercase(Locale.ROOT).take(254)
    if (!allowAttempt("request-$type", request.tenantId, email, host)) return AuthResult(status = 429, code = "rate_limited")
    val account = findAccount(request.tenantId, email)
    if (account?.decode<Account>()?.active != true || !tenantAvailable(request.tenantId) ||
        otp && !loginEnabled(request.tenantId, "otpLogin", false)) return AuthResult(Accepted())
    val code = if (otp) SecureRandom().nextInt(1_000_000).toString().padStart(6, '0') else null
    val challenge = issueChallenge(account, type, if (otp) 300 else 1800, otp = code)
    queueDelivery(account, challenge)
    return AuthResult(Accepted())
}

internal fun Tx.consumeOtp(request: OtpRequest, host: String): AuthResult<Tokens> {
    val email = request.email.trim().lowercase(Locale.ROOT).take(254)
    if (!allowAttempt("consume-otp", request.tenantId, email, host)) return AuthResult(status = 429, code = "rate_limited")
    val account = findAccount(request.tenantId, email)
    if (account?.decode<Account>()?.active != true || !tenantAvailable(request.tenantId) ||
        !loginEnabled(request.tenantId, "otpLogin", false)) return AuthResult()
    val record = list("auth_challenge", request.tenantId, ownerId = account.id).firstOrNull {
        val data = it.decode<ChallengeData>()
        data.type == "otp" && !data.consumed && !expired(data.expiresAt)
    } ?: return AuthResult()
    val data = record.decode<ChallengeData>()
    if (data.attempts >= 5) return AuthResult()
    if (!sameSecret(data.secretHash, digest("${record.id}:${request.code}"))) {
        update(record, body(data.copy(attempts = data.attempts + 1)))
        return AuthResult()
    }
    consumeChallenge(record)
    return AuthResult(issueSession(account, request.device))
}
