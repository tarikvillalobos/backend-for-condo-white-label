package com.community.api.v1.platform

import com.community.api.v1.*
import kotlinx.serialization.json.*

internal fun V1Context.createNodesBulk(): V1Response {
    val nodes = input.arr("nodes").map { it.jsonObject }
    val refs = nodes.map { it.string("ref")!! }
    if (refs.size != refs.toSet().size) fail(422, "DUPLICATE_NODE_REF", "Referências duplicadas no lote")
    val unresolved = nodes.toMutableList()
    val resolved = mutableMapOf<String, String>()
    val root = rootNode()
    val types = store.list("node_type", condominiumId()).associateBy { it.data.string("code") }
    while (unresolved.isNotEmpty()) {
        val ready = unresolved.filter { it.string("parentRef") == null || it.string("parentRef") in resolved }
        if (ready.isEmpty()) fail(422, "INVALID_PARENT_REF", "O lote contém ciclo ou referência de pai inexistente")
        for (data in ready) {
            if (data.string("parentRef") != null && data.string("parentId") != null) {
                fail(422, "AMBIGUOUS_PARENT", "Informe parentRef ou parentId")
            }
            val type = types[data.string("typeCode")] ?: fail(422, "INVALID_NODE_TYPE", "Tipo de nó desconhecido")
            val parentId = data.string("parentRef")?.let { resolved[it] } ?: data.string("parentId") ?: root.id
            val input = JsonObject(data - setOf("ref", "parentRef", "typeCode")).plusFields("parentId" to parentId, "typeId" to type.id)
            resolved[data.string("ref")!!] = createStructureNode(input).id
        }
        unresolved.removeAll(ready.toSet())
    }
    return V1Response(structureNodeView(this, root, 32), 201)
}

internal fun V1Context.deactivateNodes(): V1Response {
    val target = store.get("node", pathId("nodeId"), condominiumId())
    if (target.data.string("parentId") == null) fail(409, "ROOT_REQUIRED", "A raiz do condomínio não pode ser removida")
    val ids = nodeDescendants(target.id)
    if (query["cascade"] != "true" && ids.size > 1) fail(409, "NODE_HAS_CHILDREN", "Informe cascade para desativar a subárvore")
    if (store.list("membership", condominiumId()).any { it.data.string("nodeId") in ids && it.data.string("status") in setOf("active", "pending") }) {
        fail(409, "NODE_HAS_MEMBERSHIPS", "A subárvore possui vínculos ativos ou pendentes")
    }
    if (store.list("parcel", condominiumId()).any { it.data.string("nodeId") in ids && it.data.string("status") in setOf("waiting", "manual") }) {
        fail(409, "NODE_HAS_PARCELS", "A subárvore possui encomendas pendentes")
