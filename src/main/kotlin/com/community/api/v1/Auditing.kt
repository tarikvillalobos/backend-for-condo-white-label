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

fun appendAudit(c: V1Context, action: String, record: Record? = null, outcome: String = "success", details: JsonObject = obj(), source: String = "server", occurredAt: String? = null): JsonObject {
    val chainScope = c.locationId?.let { "condominium:$it" } ?: "brand"
    c.tx.lock("audit:${c.tenantId}:${c.brandId}:$chainScope")
    val previous = c.tx.connection.prepareStatement("SELECT hash FROM audit_log WHERE tenant_id = ? AND brand_id = ? AND chain_scope = ? ORDER BY sequence DESC LIMIT 1").use {
        it.setString(1, c.tenantId); it.setString(2, c.brandId); it.setString(3, chainScope)
        it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else "" }
    }
    val id = UUID.randomUUID().toString()
    val at = Instant.now().toString()
    val actor = c.principal
    val operation = Contract.operations.firstOrNull { it.id == c.operationId }?.definition
    val metadata = operation?.get("x-audit")?.jsonObject.orEmpty()
    val entry = obj("id" to id, "category" to (metadata["category"] ?: JsonPrimitive("system")), "action" to action,
        "severity" to (metadata["severity"] ?: JsonPrimitive("info")), "outcome" to outcome,
        "actor" to auditActor(actor), "onBehalfOf" to null, "organizationId" to c.path["organizationId"],
        "condominiumId" to c.locationId, "node" to null,
        "target" to record?.let { obj("type" to it.kind.removePrefix("v1_"), "id" to it.id, "label" to null) },
        "channel" to if (actor?.deviceId != null) "device" else "api", "requestId" to c.requestId,
        "changes" to null, "details" to redact(details), "createdAt" to at, "source" to source, "occurredAt" to (occurredAt ?: at))
    val hash = Secrets.hash(previous + entry.toString())
    val saved = JsonObject(entry + obj("hash" to hash))
    c.tx.connection.prepareStatement("INSERT INTO audit_log (id,tenant_id,brand_id,location_id,request_id,actor_id,action,target_type,target_id,created_at,previous_hash,hash,payload) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)").use {
        listOf(id,c.tenantId,c.brandId,c.locationId,c.requestId,actor?.userId,action,record?.kind?.removePrefix("v1_"),record?.id,at,previous,hash,saved.toString()).forEachIndexed { index, value -> it.setObject(index + 1, value) }
        it.executeUpdate()
    }
    return saved
}

fun auditActor(actor: V1Principal?): JsonObject = obj(
    "kind" to if (actor?.deviceId != null) "device" else if (actor?.staff == true) "staff" else if (actor != null) "user" else "anonymous",
    "userId" to actor?.userId, "deviceId" to actor?.deviceId, "name" to null,
    "role" to if (actor?.staff == true) "staff" else if (actor != null) "resident" else null, "context" to null,
)

private val sensitive = Regex("password|secret|token|credential|code|qr|cpf|phone|email|document|cipher|keyhash|proof", RegexOption.IGNORE_CASE)
fun redact(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { (key, child) -> if (sensitive.containsMatchIn(key)) JsonPrimitive("***") else redact(child) })
    is JsonArray -> JsonArray(value.map(::redact))
    else -> value
}

fun recordRequest(db: Database, requestId: String, operation: ContractOperation?, tenantId: String?, brandId: String?, locationId: String?, actor: V1Principal?, method: String, route: String, status: Int, duration: Long, code: String?, replay: Boolean = false) {
    db.scopedTx(null) { tx ->
        val at = Instant.now().toString()
        fun count(table: String): Int = tx.connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE request_id = ?").use {
            it.setString(1, requestId); it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
        val payload = obj("requestId" to requestId, "createdAt" to at, "actor" to auditActor(actor), "onBehalfOf" to null,
            "organizationId" to null, "condominiumId" to locationId, "channel" to if (actor?.deviceId != null) "device" else "api",
            "method" to method, "route" to route, "operationId" to operation?.id, "pathParams" to obj(), "statusCode" to status,
            "outcome" to if (status < 400) "success" else if (status in setOf(401,403)) "denied" else if (status >= 500) "error" else "failed",
            "problemCode" to code, "durationMs" to duration, "idempotentReplay" to replay, "appVersion" to null,
            "eventCount" to count("audit_log"), "changeCount" to count("audit_changes"))
        tx.connection.prepareStatement("INSERT INTO api_requests (request_id,tenant_id,brand_id,location_id,actor_id,operation_id,created_at,status_code,payload) VALUES (?,?,?,?,?,?,?,?,?)").use {
            listOf(requestId,tenantId,brandId,locationId,actor?.userId,operation?.id,at,status,payload.toString()).forEachIndexed { index, value -> it.setObject(index + 1, value) }
            it.executeUpdate()
        }
    }
}
