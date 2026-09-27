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
