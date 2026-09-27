package com.community.api.v1.platform

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*

internal fun structureHandlers(): Map<String, V1Handler> = mapOf(
    "listNodeTypes" to V1Handler { c -> V1Response(obj("items" to c.store.list("node_type", c.condominiumId())
        .map { c.project("NodeType", it.document()) })) },
    "createNodeType" to V1Handler { it.saveNodeType(false) },
    "updateNodeType" to V1Handler { it.saveNodeType(true) },
    "adminGetStructure" to V1Handler { c -> V1Response(structureNodeView(c, c.rootNode(), c.query["depth"]?.toIntOrNull() ?: 4)) },
    "createNode" to V1Handler { c -> V1Response(structureNodeView(c, c.createStructureNode(c.input)), 201) },
    "createNodesBulk" to V1Handler { it.createNodesBulk() },
    "updateNode" to V1Handler { it.updateStructureNode() },
    "moveNode" to V1Handler { it.moveStructureNode() },
    "deleteNode" to V1Handler { it.deactivateNodes() },
    "restoreNode" to V1Handler { it.restoreNodes() },
    "adminGetNode" to V1Handler { c ->
        val node = c.store.get("node", c.pathId("nodeId"), c.condominiumId())
        V1Response(obj("node" to structureNodeView(c, node), "residents" to c.store.list("membership", c.condominiumId(),
            filters = mapOf("nodeId" to node.id)).map { c.membershipAdminView(it) },
            "counts" to c.nodeCounts(setOf(node.id)), "countsIncludeSubtree" to c.nodeCounts(c.nodeDescendants(node.id))))
    },
)

internal fun V1Context.rootNode(): Record {
    val condo = store.get("condominium", condominiumId())
    return store.get("node", condo.data.string("rootNodeId")!!, condo.id)
}

private fun V1Context.saveNodeType(update: Boolean): V1Response {
    val id = if (update) pathId("nodeTypeId") else null
    val existing = id?.let { store.get("node_type", it, condominiumId()) }
    val code = required("code")
    if (existing != null && existing.data.string("code") != code && store.list("node", condominiumId(),
            filters = mapOf("typeId" to existing.id)).isNotEmpty()) fail(409, "NODE_TYPE_IN_USE", "Não altere o código de um tipo em uso")
    if (store.list("node_type", condominiumId()).any { it.id != id && it.data.string("code") == code }) {
        fail(409, "NODE_TYPE_EXISTS", "Já existe um tipo com esse código")
    }
    val known = store.list("node_type", condominiumId()).map { it.data.string("code") }.toSet() + code
    if (input.arr("allowedParents").any { it.jsonPrimitive.content !in known }) fail(422, "INVALID_PARENT_TYPE", "Tipo de pai desconhecido")
    val data = JsonObject(obj("allowedParents" to emptyList<String>(), "sortOrder" to 0) + input)
    val record = if (existing == null) store.create("node_type", data, condominiumId()) else platformUpdate(existing, data)
    return platformResult("NodeType", record, if (update) 200 else 201)
}

internal fun V1Context.createStructureNode(data: JsonObject): Record {
    val type = store.get("node_type", data.string("typeId")!!, condominiumId())
    val parentId = data.string("parentId")
    checkNodeParent(type, parentId)
    val label = data.string("label")!!.trim()
    checkSiblingLabel(parentId, label)
    return store.create("node", JsonObject(obj("code" to null, "attributes" to obj(), "sortOrder" to 0,
        "receivesAsEntity" to false, "active" to true) + data).plusFields("label" to label,
        "typeCode" to type.data.string("code")), condominiumId())
}

private fun V1Context.checkNodeParent(type: Record, parentId: String?) {
    if (parentId == null) {
