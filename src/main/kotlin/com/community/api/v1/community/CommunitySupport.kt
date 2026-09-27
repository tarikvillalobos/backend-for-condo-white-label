package com.community.api.v1.community

import com.community.api.core.Record
import com.community.api.core.activeMemberships
import com.community.api.v1.*
import kotlinx.serialization.json.*

internal fun V1Context.id(key: String) = path[key] ?: fail(400, "VALIDATION_ERROR", "Identificador ausente: $key")
internal fun V1Context.location() = locationId ?: fail(400, "CONDOMINIUM_REQUIRED", "Condomínio obrigatório")
internal fun V1Context.owned(row: Record): Record = row.also {
    if (membershipId != null && (it.ownerId != userId || it.data.text("membershipId") != membershipId))
        fail(404, "NOT_FOUND", "Recurso não encontrado")
}
internal fun V1Context.record(kind: String, key: String) = owned(store.get(kind, id(key), locationId))
internal fun V1Context.personName(id: String? = principal?.userId): String = id?.let {
    store.find("account", it)?.data?.text("name") ?: tx.get("account", it, tenantId)?.data?.text("name")
} ?: "Sistema"
internal fun V1Context.node(id: String?): JsonElement {
    if (id == null) return JsonNull
    val row = store.get("node", id, locationId)
    return obj("id" to row.id, "type" to (row.data.text("type") ?: row.data.text("typeCode") ?: "unit"), "label" to row.data.text("label"))
}
internal fun V1Context.inSubtree(candidate: String?, ancestor: String?): Boolean {
    if (ancestor == null) return true
    var current = candidate
    val seen = mutableSetOf<String>()
    while (current != null && seen.add(current)) {
        if (current == ancestor) return true
        current = store.find("node", current, locationId)?.data?.text("parentId")
    }
    return false
}
internal fun V1Context.checkedNode(input: JsonObject = this.input, required: Boolean = false): String? {
    val value = input.text("nodeId") ?: if (membershipId != null) unitId else null
    if (required && value == null) fail(422, "NODE_REQUIRED", "O vínculo precisa estar associado a um nó")
    value?.let { store.get("node", it, locationId) }
    if (membershipId != null && !inSubtree(value, unitId)) fail(403, "NODE_OUTSIDE_SCOPE", "Nó fora do vínculo")
    return value
}
internal fun V1Context.visible(data: JsonObject): Boolean = membershipId == null || data.array("targetNodeIds").let { targets ->
