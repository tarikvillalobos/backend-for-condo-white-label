package com.community.api.identity

import com.community.api.core.*

fun Tx.revokeAccountCredentials(tenantId: String, userId: String) {
    revokeSessions(tenantId, userId)
    list("auth_challenge", tenantId, ownerId = userId).filter {
        !it.decode<ChallengeData>().consumed
    }.forEach { consumeChallenge(it) }
}
