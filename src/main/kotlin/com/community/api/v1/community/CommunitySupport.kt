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
    targets.isEmpty() || targets.any { inSubtree(unitId, it.jsonPrimitive.content) }
}
internal fun V1Context.save(kind: String, data: JsonObject, owner: String? = principal?.userId): Record =
    store.create(kind, if ("membershipId" in data) data else data.merge(obj("membershipId" to membershipId)), locationId, owner).also { audit("$kind.created", it) }
internal fun V1Context.change(row: Record, data: JsonObject, action: String = "${row.kind.removePrefix("v1_")}.updated"): Record {
    if (header("If-Match") != null || operationId in setOf("updateVehicle", "updatePet", "updateVisitor", "updateAccessInvite", "adminUpdateWorkOrder")) requireVersion(row)
    return store.update(row, row.data.merge(data)).also { audit(action, it) }
}
internal fun V1Context.remove(row: Record): V1Response {
    store.delete(row)
    audit("${row.kind.removePrefix("v1_")}.deleted", row)
    return V1Response(status = 204)
}
internal fun Record.metadata() = data.merge(obj("id" to id, "createdAt" to createdAt, "updatedAt" to updatedAt, "version" to version))
internal fun V1Context.view(schema: String, row: Record, extras: JsonObject = obj()) = project(schema, row.metadata().merge(extras))
internal fun V1Context.result(schema: String, row: Record, status: Int = 200, extras: JsonObject = obj()) =
    V1Response(view(schema, row, extras), status, mapOf("ETag" to "\"${row.version}\""))
internal fun V1Context.listResponse(kind: String, own: Boolean = false, filters: Map<String, String> = emptyMap(), transform: (Record) -> JsonElement): V1Response {
    val constraints = filters + if (own) mapOf("membershipId" to (membershipId ?: fail(403, "MEMBERSHIP_REQUIRED", "Vínculo obrigatório"))) else emptyMap()
    val rendered = mutableMapOf<String, JsonElement>()
    val page = page(kind, locationId, if (own) userId else null, constraints, predicate = { row ->
        val value = transform(row)
        val accepts = value != JsonNull && queryMatches(row, value as? JsonObject ?: obj())
        if (accepts) rendered[row.id] = value
        accepts
    }, transform = { row -> rendered.remove(row.id) ?: transform(row) })
    return V1Response(page)
}
internal fun V1Context.simpleList(kind: String, schema: String, predicate: (Record) -> Boolean = { true }) =
    V1Response(obj("items" to JsonArray(store.list(kind, locationId).filter(predicate).map { view(schema, it) })))
internal fun V1Context.validateTargets(data: JsonObject) { data.array("targetNodeIds").forEach { store.get("node", it.jsonPrimitive.content, locationId) } }
private fun V1Context.queryMatches(row: Record, output: JsonObject): Boolean {
    for (field in listOf("status", "kind", "category", "species", "area")) {
        val expected = query[field] ?: continue
        if ((output.text(field) ?: row.data.text(field)) != expected) return false
    }
    if (query["nodeId"]?.let { !inSubtree(row.data.text("nodeId"), it) } == true) return false
    if (query["q"]?.let { !output.toString().contains(it, ignoreCase = true) } == true) return false
    if (query["incident"] == "true" && !row.data.flag("incident")) return false
    val date = row.data.text("occurredAt") ?: row.data.text("startsAt") ?: row.data.text("scheduledAt") ?: row.createdAt
    if (query["since"]?.let { timestamp(date).isBefore(timestamp(it)) } == true) return false
    if (query["until"]?.let { timestamp(date).isAfter(timestamp(it)) } == true) return false
    return true
}
internal fun V1Context.requireStaffUser(id: String?) {
    if (id == null) return
    val assigned = store.list("staff_assignment", locationId).any { it.data.text("userId") == id && it.data.text("status") != "revoked" }
    val legacy = tx.activeMemberships(tenantId, id).any { it.locationId == locationId || it.locationId == null }
    if (!assigned && !legacy) fail(422, "INVALID_ASSIGNEE", "Responsável sem vínculo ativo no condomínio")
}
