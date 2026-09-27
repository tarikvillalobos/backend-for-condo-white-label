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
