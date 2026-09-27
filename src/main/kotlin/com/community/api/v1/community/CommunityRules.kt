package com.community.api.v1.community

import com.community.api.core.ApiException
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.flag(key: String, default: Boolean = false) = (get(key) as? JsonPrimitive)?.booleanOrNull ?: default
internal fun JsonObject.number(key: String, default: Int = 0) = (get(key) as? JsonPrimitive)?.intOrNull ?: default
internal fun JsonObject.array(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())
internal fun obj(vararg values: Pair<String, Any?>): JsonObject = buildJsonObject {
    values.forEach { (key, value) -> put(key, when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }) }
}
