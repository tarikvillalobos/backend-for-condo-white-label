package com.community.api.v1.identity

import com.community.api.core.*
import com.community.api.identity.*
import com.community.api.v1.*
import kotlinx.serialization.json.*

fun authenticateV1Session(tx: Tx, tenantId: String, brandId: String, token: String): Actor {
    val actor = tx.authenticate(token)
    val metadata = V1Store(tx, tenantId, brandId).find("session", actor.sessionId)
    if (actor.tenantId != tenantId || metadata?.data?.string("brandId") != brandId) {
        identityFailure(401, "SESSION_REVOKED", "Sessão inválida para esta marca")
    }
    return actor
}

internal fun V1Context.identityStaff(user: Record): List<Record> =
    store.list("staff_assignment", ownerId = user.id).filter {
        it.data.string("status") == "active" && it.data.string("brandId") == brandId
    }

internal fun V1Context.issueIdentitySession(user: Record): JsonObject {
    val tokens = tx.issueSession(user, identityHeader("User-Agent") ?: "app")
    val id = tokens.accessToken.split('.')[1]
    val staff = identityStaff(user)
    val requiresMfa = staff.any { it.data["mfaRequired"]?.jsonPrimitive?.booleanOrNull == true }
    val metadata = obj("brandId" to brandId, "staff" to (staff.isNotEmpty() && !requiresMfa),
        "channel" to "app", "lastSeenAt" to now.toString())
    store.create("session", metadata, ownerId = user.id, id = id)
    val challenge = if (requiresMfa) {
        requireEmailChannel("email")
        issueIdentityChallenge("staff_mfa", user, user.decode<Account>().email, id)
    } else null
    return identityTokens(user, tokens, challenge)
}

internal fun V1Context.identityTokens(user: Record, tokens: Tokens, challenge: JsonObject? = null): JsonObject {
    val id = tokens.accessToken.split('.')[1]
    val session = tx.get("session", id, tenantId)!!.decode<SessionData>()
    val staff = store.get("session", id).data["staff"]?.jsonPrimitive?.booleanOrNull == true
