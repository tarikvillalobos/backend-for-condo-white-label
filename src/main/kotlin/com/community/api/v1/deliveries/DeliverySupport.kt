package com.community.api.v1.deliveries

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.time.Instant

internal fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.flag(name: String): Boolean = (get(name) as? JsonPrimitive)?.booleanOrNull == true
internal fun JsonObject.int(name: String): Int? = (get(name) as? JsonPrimitive)?.intOrNull
internal fun JsonObject.array(name: String): JsonArray = get(name) as? JsonArray ?: JsonArray(emptyList())
internal fun JsonObject.changed(vararg values: Pair<String, JsonElement>): JsonObject = JsonObject(this + values)
internal fun value(text: String?): JsonElement = text?.let(::JsonPrimitive) ?: JsonNull
internal fun instant(text: String?): Instant = try { Instant.parse(text) }
    catch (_: Exception) { throw com.community.api.core.ApiException(422, "VALIDATION_ERROR", "A valid timestamp is required") }
internal fun V1Context.condo(): String = locationId ?: input.text("condominiumId") ?: fail(404, "NOT_FOUND", "Condominium not found")
internal fun V1Context.member(id: String): Record = store.get("membership", id, condo()).also {
    if (it.data.text("status") != "active") fail(422, "DELEGATE_NOT_ELIGIBLE", "An active membership in the same condominium is required")
}
