package com.community.api.v1

import com.community.api.core.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

fun Tx.requestMetadata(requestId: String, operationId: String, actor: V1Principal?) {
    if (!postgres) return
    mapOf("request_id" to requestId, "operation_id" to operationId, "actor_id" to actor?.userId,
        "actor_kind" to if (actor?.deviceId != null) "device" else if (actor?.staff == true) "staff" else if (actor != null) "user" else "anonymous",
        "actor_role" to if (actor?.staff == true) "staff" else "resident").forEach { (key, value) ->
        connection.prepareStatement("SELECT set_config(?, ?, true)").use {
            it.setString(1, "app.$key"); it.setString(2, value.orEmpty()); it.execute()
        }
    }
}

fun appendAudit(c: V1Context, action: String, record: Record? = null, outcome: String = "success", details: JsonObject = obj()): JsonObject {
    c.tx.lock("audit:${c.tenantId}:${c.brandId}")
