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
