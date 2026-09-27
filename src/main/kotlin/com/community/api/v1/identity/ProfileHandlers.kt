package com.community.api.v1.identity

import com.community.api.core.*
import com.community.api.identity.*
import com.community.api.v1.*
import kotlinx.serialization.json.*

internal fun profileHandlers(): Map<String, V1Handler> = mapOf(
    "getProfile" to V1Handler { V1Response(it.identityProfile()) },
    "updateCommunicationPreferences" to V1Handler { it.updatePreferences() },
    "changePassword" to V1Handler { it.changeIdentityPassword() },
    "requestContactChange" to V1Handler { it.requestIdentityContactChange() },
    "resendContactChange" to V1Handler { it.resendIdentityChallenge("contact_change", true) },
    "verifyContactChange" to V1Handler { it.verifyIdentityContactChange() },
    "requestStepUpChallenge" to V1Handler { it.requestStepUp() },
    "verifyStepUp" to V1Handler { it.verifyStepUp() },
    "listMySessions" to V1Handler { it.listIdentitySessions() },
    "revokeMySession" to V1Handler { c ->
        val id = c.identityPath("sessionId")
        c.store.get("session", id).takeIf { it.ownerId == c.userId }
            ?: c.fail(404, "SESSION_NOT_FOUND", "Sessão não encontrada")
        c.revokeIdentitySession(id)
        V1Response(status = 204)
    },
    "revokeOtherSessions" to V1Handler { it.revokeOtherIdentitySessions(); V1Response(status = 204) },
)

internal fun V1Context.identityProfile(user: Record = account()): JsonObject {
    val account = user.decode<Account>()
    val data = profileData(user)
    val memberships = store.list("membership", ownerId = user.id).filter {
        it.data.string("status") == "active"
    }.map { membershipView(this, it) }
    return obj("id" to user.id, "name" to account.name, "phone" to data["phone"],
        "phoneVerifiedAt" to data["phoneVerifiedAt"], "email" to account.email.takeIf { it.isNotBlank() },
        "emailVerifiedAt" to data["emailVerifiedAt"], "preferences" to data["preferences"],
        "memberships" to memberships, "staffAssignments" to staffAssignmentsView(this, user.id))
}

private fun V1Context.updatePreferences(): V1Response {
    val user = account()
    val data = profileData(user)
    val current = data["preferences"] as JsonObject
    saveProfile(user, data.with("preferences" to JsonObject(current + input)))
    return V1Response(identityProfile(user))
}

private fun V1Context.changeIdentityPassword(): V1Response {
    if (!identityRate("v1-login", userId)) return rateLimited()
    val user = account()
    val account = user.decode<Account>()
    if (!Passwords.verify(identityInput("currentPassword"), account.passwordHash)) {
        return identityError(401, "INVALID_CREDENTIALS", "Senha inválida")
    }
    tx.update(user, body(account.copy(passwordHash = Passwords.hash(identityInput("newPassword")))))
    revokeOtherIdentitySessions()
    consumeOtherChallenges(user.id)
    return V1Response(status = 204)
}

private fun V1Context.requestIdentityContactChange(): V1Response {
    val channel = identityInput("channel")
    val destination = checkedContact(identityInput("contact"), channel)
    requireEmailChannel(channel)
    if (!identityRate("v1-contact", userId)) return rateLimited()
    return V1Response(issueIdentityChallenge("contact_change", account(), destination, principal!!.sessionId), 202)
}

private fun V1Context.verifyIdentityContactChange(): V1Response {
    val id = identityPath("challengeId")
    val meta = challengeMetadata(id, "contact_change", true)
    val destination = meta.data.string("destination")!!
    val verified = verifyIdentityChallenge(id, "contact_change", true)
    verified.failure?.let { return it }
    val user = verified.account!!
    val existing = findIdentity("email", destination)
    if (existing != null && existing.id != user.id) fail(409, "CONTACT_ALREADY_USED", "Contato indisponível")
    val oldEmail = user.decode<Account>().email
    val updated = tx.update(user, body(user.decode<Account>().copy(email = destination)))
    if (oldEmail != destination) {
        tx.get("v1_identifier", identityIndexId(tenantId, "email", oldEmail), tenantId)?.let { tx.delete(it) }
    }
    indexIdentityAccount(tx, updated)
    saveProfile(updated, profileData(updated).with("emailVerifiedAt" to now.toString()))
    consumeOtherChallenges(user.id)
    revokeOtherIdentitySessions()
    return V1Response(identityProfile(updated))
}

private fun V1Context.requestStepUp(): V1Response {
    val user = account()
    if (profileData(user).string("emailVerifiedAt") == null) {
        fail(403, "VERIFIED_CONTACT_REQUIRED", "É necessário um contato verificado")
    }
    requireEmailChannel("email")
    if (!identityRate("v1-step-up", userId)) return rateLimited()
    return V1Response(issueIdentityChallenge("step_up", user, user.decode<Account>().email, principal!!.sessionId), 202)
}

private fun V1Context.verifyStepUp(): V1Response {
