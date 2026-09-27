package com.community.api.v1

import com.community.api.core.*
import kotlinx.serialization.json.*
import java.time.Instant

fun interface V1Handler { fun handle(context: V1Context): V1Response }

data class V1Response(
    val body: JsonElement = JsonNull,
    val status: Int = 200,
    val headers: Map<String, String> = emptyMap(),
)

data class V1Principal(
    val actor: Actor? = null,
    val userId: String? = actor?.userId,
    val sessionId: String? = actor?.sessionId,
    val staff: Boolean = false,
    val deviceId: String? = null,
    val permissions: Set<String> = emptySet(),
)

class V1Context(
    val tx: Tx,
    val operationId: String,
    val tenantId: String,
    val brandId: String,
    val requestId: String,
    val input: JsonObject = obj(),
    val path: Map<String, String> = emptyMap(),
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val principal: V1Principal? = null,
    val locationId: String? = path["condominiumId"],
    val membership: Record? = null,
    val now: Instant = Instant.now(),
) {
    val store = V1Store(tx, tenantId, brandId)
    val userId: String get() = principal?.userId ?: fail(401, "SESSION_EXPIRED", "Authentication required")
