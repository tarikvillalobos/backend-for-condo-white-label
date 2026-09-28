package com.community.api.v1.identity

import com.community.api.core.*
import com.community.api.identity.*
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.Instant

fun identityHandlers(): Map<String, V1Handler> = mapOf(
    "loginWithPassword" to V1Handler { it.passwordLogin() },
    "requestLoginChallenge" to V1Handler { it.requestLoginChallenge() },
    "resendLoginChallenge" to V1Handler { it.resendIdentityChallenge("login", false) },
    "verifyLoginChallenge" to V1Handler { it.verifyLoginChallenge() },
    "refreshSession" to V1Handler { it.refreshIdentitySession() },
    "requestPasswordRecovery" to V1Handler { it.requestPasswordRecovery() },
    "verifyPasswordRecovery" to V1Handler { it.verifyPasswordRecovery() },
    "verifyStaffMfa" to V1Handler { it.verifyStaffMfa() },
    "logoutSession" to V1Handler { c -> c.revokeIdentitySession(c.principal!!.sessionId!!); V1Response(status = 204) },
) + profileHandlers() + invitationHandlers() + privacyHandlers() + deviceHandlers()

private fun V1Context.passwordLogin(): V1Response {
    val identifier = identityInput("identifier")
    if (!identityRate("v1-login", identifier.lowercase())) return rateLimited()
    val (type, value) = checkedIdentifier(identifier)
    val user = findIdentity(type, value)
    val account = user?.decode<Account>()
    val valid = Passwords.verify(identityInput("password"), account?.passwordHash)
    val methods = store.find("brand", brandId)?.data?.get("authMethods") as? JsonArray
    if (!valid || account?.active != true || methods != null && JsonPrimitive("password") !in methods) {
        return identityError(401, "INVALID_CREDENTIALS", "Identificador ou senha inválidos")
    }
    return V1Response(issueIdentitySession(user!!))
}

private fun V1Context.requestLoginChallenge(): V1Response {
    val channel = identityInput("channel")
    val contact = checkedContact(identityInput("contact"), channel)
    val cpf = checkedCpf(identityInput("cpf"))
    requireEmailChannel(channel)
    if (!identityRate("v1-challenge", contact) || !identityRate("v1-cpf", cpf)) return rateLimited()
    val user = findIdentity(if (channel == "email") "email" else "phone", contact)?.takeIf {
        it.decode<Account>().active && findIdentity("cpf", cpf)?.id == it.id
    }
    return V1Response(issueIdentityChallenge("login", user, contact), 202)
}

private fun V1Context.verifyLoginChallenge(): V1Response {
    val verified = verifyIdentityChallenge(identityPath("challengeId"), "login")
    verified.failure?.let { return it }
    val user = verified.account!!
    saveProfile(user, profileData(user).with("emailVerifiedAt" to now.toString()))
    return V1Response(issueIdentitySession(user))
}

private fun V1Context.requestPasswordRecovery(): V1Response {
    val (type, value) = checkedIdentifier(identityInput("identifier"))
    val channel = identityInput("channel")
    requireEmailChannel(channel)
    if (!identityRate("v1-recovery", value)) return rateLimited()
    val user = findIdentity(type, value)?.takeIf { it.decode<Account>().active }
    val destination = user?.decode<Account>()?.email ?: value
    val masked = if (type == "email") maskedContact(value, "email") else "***"
    return V1Response(issueIdentityChallenge("password_recovery", user, destination, displayDestination = masked), 202)
}

private fun V1Context.verifyPasswordRecovery(): V1Response {
    val password = identityInput("newPassword")
    Passwords.validate(password)
    val verified = verifyIdentityChallenge(identityPath("challengeId"), "password_recovery")
    verified.failure?.let { return it }
    val user = verified.account!!
    val updated = tx.update(user, body(user.decode<Account>().copy(passwordHash = Passwords.hash(password))))
    saveProfile(updated, profileData(updated).with("emailVerifiedAt" to now.toString()))
    tx.list("session", tenantId, ownerId = user.id).forEach { revokeIdentitySession(it.id) }
    consumeOtherChallenges(user.id)
    return V1Response(issueIdentitySession(updated))
}

internal fun V1Context.consumeOtherChallenges(id: String) {
    tx.list("auth_challenge", tenantId, ownerId = id).filter { !it.decode<ChallengeData>().consumed }
        .forEach { tx.consumeChallenge(it) }
}

private fun V1Context.verifyStaffMfa(): V1Response {
    val verified = verifyIdentityChallenge(identityPath("challengeId"), "staff_mfa", true)
    verified.failure?.let { return it }
    val user = verified.account!!
    saveProfile(user, profileData(user).with("emailVerifiedAt" to now.toString()))
    if (identityStaff(user).isEmpty()) fail(403, "STAFF_ASSIGNMENT_REQUIRED", "Atribuição de equipe indisponível")
    val id = principal!!.sessionId!!
    val metadata = store.get("session", id)
    store.update(metadata, metadata.data.with("staff" to true, "mfaVerifiedAt" to now.toString()))
    val session = tx.get("session", id, tenantId)!!
    val old = session.decode<SessionData>()
    val access = "$tenantId.$id.${secretToken()}"
    val refresh = "$tenantId.$id.${secretToken()}"
    tx.update(session, body(old.copy(accessHash = digest(access), refreshHash = digest(refresh),
        accessExpiresAt = now.plusSeconds(900).toString(), verifiedAt = now.toString(),
        usedRefreshHashes = old.usedRefreshHashes + old.refreshHash)))
    return V1Response(identityTokens(user, Tokens(access, refresh)))
}
