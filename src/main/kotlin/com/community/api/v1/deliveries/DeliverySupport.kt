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
internal fun V1Context.person(id: String?): JsonElement {
    if (id == null) return JsonNull
    val member = store.find("membership", id) ?: return JsonNull
    val account = member.ownerId?.let { tx.get("account", it, tenantId) }
    return obj("membershipId" to member.id, "name" to (member.data.text("name") ?: account?.data?.text("name").orEmpty()))
}
internal fun V1Context.node(id: String?): JsonElement {
    if (id == null) return JsonNull
    val row = store.get("node", id)
    return obj("id" to row.id, "type" to (row.data.text("type") ?: row.data.text("typeCode")), "label" to row.data.text("label"))
}
internal fun V1Context.nodePath(id: String?): JsonArray {
    val result = mutableListOf<JsonElement>()
    val seen = mutableSetOf<String>()
    var current = id
    while (current != null && seen.add(current)) {
        result.add(0, node(current))
        current = store.get("node", current).data.text("parentId")
    }
    return JsonArray(result)
