package com.community.api.v1.identity

import com.community.api.core.*
import com.community.api.identity.*
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

internal data class VerifiedChallenge(val account: Record?, val failure: V1Response? = null)

internal fun V1Context.requireEmailChannel(channel: String) {
    if (channel != "email") fail(501, "CHANNEL_UNAVAILABLE", "O provedor deste canal não está configurado")
    if (MailConfig.fromEnvironment() == null) {
        fail(503, "CHANNEL_UNAVAILABLE", "O serviço de e-mail não está configurado")
    }
}

internal fun V1Context.issueIdentityChallenge(
