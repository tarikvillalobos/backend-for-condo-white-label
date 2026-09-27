package com.community.api.v1.reservations

import com.community.api.core.ApiException
import kotlinx.serialization.json.*
import java.time.*

internal fun bookingError(code: String, detail: String, status: Int = 422): Nothing =
    throw ApiException(status, code, detail)
internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.number(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
internal fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true
internal fun JsonObject.objectAt(key: String): JsonObject = get(key) as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.arrayAt(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())
internal fun JsonObject.changed(vararg values: Pair<String, JsonElement>): JsonObject = JsonObject(this + values)
internal fun timestamp(value: String?): Instant = try { Instant.parse(value) }
    catch (_: Exception) { bookingError("VALIDATION_ERROR", "A valid UTC timestamp is required") }
internal fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull

internal object BookingRules {
    fun validate(space: JsonObject) {
