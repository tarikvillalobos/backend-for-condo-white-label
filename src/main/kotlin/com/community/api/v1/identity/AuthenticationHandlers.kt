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
