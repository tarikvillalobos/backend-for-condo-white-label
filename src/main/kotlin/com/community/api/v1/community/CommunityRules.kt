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
internal fun JsonObject.merge(other: JsonObject) = JsonObject(this + other)
internal fun failure(code: String, detail: String, status: Int = 409): Nothing = throw ApiException(status, code, detail)
internal fun now() = Instant.now().toString()
internal fun timestamp(value: String): Instant = runCatching { Instant.parse(value) }.getOrElse {
    failure("VALIDATION_ERROR", "Data e hora inválidas", 422)
}
internal fun window(data: JsonObject, maxSeconds: Long? = null) {
    val start = timestamp(data.text("validFrom") ?: data.text("startsAt") ?: return)
    val end = timestamp(data.text("validUntil") ?: data.text("endsAt") ?: return)
    if (!end.isAfter(start) || (maxSeconds != null && end.epochSecond - start.epochSecond > maxSeconds))
        failure("INVALID_TIME_RANGE", "A janela de tempo é inválida", 422)
}
internal fun vaccinationDates(data: JsonObject) {
    val applied = LocalDate.parse(data.text("appliedAt"))
    if (applied.isAfter(LocalDate.now())) failure("INVALID_VACCINATION_DATE", "A aplicação não pode estar no futuro", 422)
    data.text("nextDueAt")?.let {
        if (!LocalDate.parse(it).isAfter(applied)) failure("INVALID_VACCINATION_DATE", "A próxima dose deve ser posterior à aplicação", 422)
    }
}
internal val workOrderTransitions = mapOf(
