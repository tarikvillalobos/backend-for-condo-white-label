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
    val membershipId: String? get() = membership?.id ?: path["membershipId"]
    val unitId: String? get() = membership?.data?.string("nodeId")

    fun fail(status: Int, code: String, message: String): Nothing = throw ApiException(status, code, message)
    fun requirePermission(vararg codes: String) {
        val permissions = principal?.permissions.orEmpty()
        if ("*" !in permissions && codes.none { it in permissions }) fail(403, "ACCESS_DENIED", "Permission required")
    }
    fun requireVersion(record: Record) {
        val supplied = header("If-Match") ?: fail(428, "VERSION_REQUIRED", "Supply the current ETag in If-Match")
        if (supplied != "\"${record.version}\"") fail(412, "VERSION_CONFLICT", "Resource changed; reload before retrying")
    }
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    fun project(schema: String, value: JsonObject): JsonObject = Contract.project(schema, value)
    fun seal(value: String): String = Secrets.seal(value)
    fun unseal(value: String): String = Secrets.unseal(value)
    fun hash(value: String): String = Secrets.hash(value)
    fun audit(action: String, record: Record) { store.audit(this, action, record) }
    fun fileUrl(fileKey: String): String = signedFileUrl(this, fileKey)
}

fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { it.first to element(it.second) })
fun element(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Instant -> JsonPrimitive(value.toString())
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to element(it.value) })
    is Iterable<*> -> JsonArray(value.map(::element))
    is Array<*> -> JsonArray(value.map(::element))
    else -> error("Unsupported JSON value ${value.javaClass.simpleName}")
}

fun Record.document(): JsonObject = JsonObject(data + obj("id" to id, "version" to version, "createdAt" to createdAt, "updatedAt" to updatedAt))
