package com.community.api.v1

import com.community.api.core.json
import kotlinx.serialization.json.*
import java.util.UUID

fun V1Context.idempotent(operation: ContractOperation, handler: V1Handler): Pair<V1Response,Boolean> {
    val required = operation.definition["parameters"]!!.jsonArray.map { Contract.resolve(it.jsonObject) }
        .any { it.string("name") == "Idempotency-Key" && it["required"] == JsonPrimitive(true) }
    if (!required) return handler.handle(this) to false
    val key = header("Idempotency-Key") ?: fail(400,"VALIDATION_ERROR","Idempotency-Key is required")
    if (runCatching { UUID.fromString(key) }.isFailure || key.length != 36) fail(422,"VALIDATION_ERROR","Idempotency-Key must be a UUID")
    val id = hash("$tenantId:$brandId:${principal?.userId}:${principal?.deviceId}:$operationId:${path.toSortedMap()}:$key")
    val fingerprint = Secrets.sign(input.toString() + ":" + (header("If-Match") ?: ""))
    tx.lock("idempotency:$id")
    val previous = tx.connection.prepareStatement("SELECT * FROM v1_idempotency WHERE id = ?").use {
        it.setString(1,id)
        it.executeQuery().use { rows ->
            if (!rows.next() || rows.getLong("expires_at") <= now.epochSecond) null else {
                if (rows.getString("fingerprint") != fingerprint) fail(409,"IDEMPOTENCY_CONFLICT","This key was used with different input")
