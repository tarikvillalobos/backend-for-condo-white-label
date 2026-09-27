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

