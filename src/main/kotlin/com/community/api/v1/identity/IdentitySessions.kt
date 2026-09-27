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
