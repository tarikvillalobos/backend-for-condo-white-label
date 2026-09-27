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
