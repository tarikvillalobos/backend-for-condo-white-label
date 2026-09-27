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
    val permissions = mutableSetOf("profile.read", "profile.manage", "sessions.manage")
    if (staff) identityStaff(user).forEach { assignment ->
        (assignment.data["permissions"] as? JsonArray)?.mapNotNullTo(permissions) { it.jsonPrimitive.contentOrNull }
    }
    return obj("tokenType" to "Bearer", "accessToken" to tokens.accessToken,
        "refreshToken" to tokens.refreshToken, "accessExpiresAt" to session.accessExpiresAt,
        "refreshExpiresAt" to session.expiresAt, "userId" to user.id, "brandId" to brandId,
        "sessionId" to id, "permissions" to permissions.toList(), "staff" to staff, "mfaChallenge" to challenge)
}

internal fun V1Context.revokeIdentitySession(id: String) {
    val session = tx.get("session", id, tenantId) ?: return
    tx.update(session, body(session.decode<SessionData>().copy(revoked = true)))
    store.list("push_registration", filters = mapOf("sessionId" to id)).forEach {
        store.update(it, it.data.with("status" to "inactive", "tokenEncrypted" to null, "tokenHash" to null))
    }
}

internal fun V1Context.refreshIdentitySession(): V1Response {
    val token = identityInput("refreshToken")
    val parts = parseToken(token) ?: return identityError(401, "REFRESH_REVOKED", "Sessão revogada")
    val meta = store.find("session", parts[1])
    if (parts[0] != tenantId || meta?.data?.string("brandId") != brandId) {
        return identityError(401, "REFRESH_REVOKED", "Sessão revogada")
    }
    val result = tx.refresh(token, identityHeader("X-Remote-Host") ?: "unknown")
    val tokens = result.value
    if (tokens == null) {
        val revoked = tx.get("session", parts[1], tenantId)?.decode<SessionData>()?.revoked == true
        if (revoked) revokeIdentitySession(parts[1])
        return if (result.status == 429) rateLimited()
        else identityError(401, "REFRESH_REVOKED", "Sessão revogada")
    }
    val user = tx.get("account", meta.ownerId!!, tenantId)!!
    return V1Response(identityTokens(user, tokens))
}

internal fun V1Context.listIdentitySessions(): V1Response {
    val items = tx.list("session", tenantId, ownerId = userId).mapNotNull { record ->
        val session = record.decode<SessionData>()
        val meta = store.find("session", record.id)?.data
        if (session.revoked || expired(session.expiresAt) || meta?.string("brandId") != brandId) null
        else obj("id" to record.id, "deviceLabel" to session.device,
            "channel" to (meta.string("channel") ?: "app"), "staff" to meta["staff"],
            "current" to (record.id == principal?.sessionId), "createdAt" to record.createdAt,
            "lastSeenAt" to (meta.string("lastSeenAt") ?: record.updatedAt), "approximateLocation" to null)
    }
    return V1Response(obj("items" to items))
}

internal fun V1Context.revokeOtherIdentitySessions() {
    tx.list("session", tenantId, ownerId = userId).filter { it.id != principal?.sessionId }
        .forEach { revokeIdentitySession(it.id) }
}
