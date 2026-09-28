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
    if (existing != null) {
        val nodes = store.list("node", condominiumId(), filters = mapOf("typeId" to existing.id))
        if (!input.bool("addressable") && store.list("membership", condominiumId()).any { member ->
                member.data.string("status") in setOf("active", "pending") && nodes.any { it.id == member.data.string("nodeId") }
            }) fail(409, "NODE_TYPE_HAS_MEMBERSHIPS", "O tipo possui nós com vínculos")
        if (nodes.any { node -> node.data.string("parentId")?.let { parentId ->
                JsonPrimitive(store.get("node", parentId).data.string("typeCode")) !in input.arr("allowedParents")
            } == true }) fail(409, "NODE_TYPE_IN_USE", "A alteração invalidaria nós existentes")
    }
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
        if (type.data.string("code") != "root" || store.list("node", condominiumId()).any { it.data.string("parentId") == null }) {
            fail(422, "ROOT_ALREADY_EXISTS", "O condomínio deve possuir uma única raiz")
        }
    } else {
        val parent = store.get("node", parentId, condominiumId())
        if (!parent.data.bool("active", true)) fail(422, "PARENT_INACTIVE", "O nó pai está inativo")
        if (JsonPrimitive(parent.data.string("typeCode")) !in type.data.arr("allowedParents")) {
            fail(422, "INVALID_PARENT_TYPE", "Este tipo não pode ser criado sob o pai informado")
        }
        if (nodePathView(this, parentId).size >= 32) fail(422, "STRUCTURE_TOO_DEEP", "Limite de profundidade atingido")
    }
}

private fun V1Context.checkSiblingLabel(parentId: String?, label: String, exceptId: String? = null) {
    if (label.isBlank()) fail(422, "INVALID_LABEL", "O rótulo não pode ficar vazio")
    if (store.list("node", condominiumId()).any { it.id != exceptId && it.data.string("parentId") == parentId &&
            it.data.bool("active", true) && it.data.string("label")?.equals(label, true) == true }) {
        fail(409, "NODE_LABEL_EXISTS", "Já existe um nó com esse rótulo sob o mesmo pai")
    }
}

private fun V1Context.updateStructureNode(): V1Response {
    val node = store.get("node", pathId("nodeId"), condominiumId())
    input.string("label")?.let { checkSiblingLabel(node.data.string("parentId"), it.trim(), node.id) }
    if (input["active"] == JsonPrimitive(false)) fail(422, "USE_NODE_DEACTIVATION", "Use DELETE para desativar com validação de dependências")
    if (input["active"] == JsonPrimitive(true) && !node.data.bool("active")) fail(422, "USE_NODE_RESTORE", "Use a operação de restauração")
    val updated = platformUpdate(node, JsonObject(node.data + input))
    return V1Response(structureNodeView(this, updated), headers = mapOf("ETag" to "\"${updated.version}\""))
}

private fun V1Context.moveStructureNode(): V1Response {
    val node = store.get("node", pathId("nodeId"), condominiumId())
    val parentId = required("newParentId")
    if (node.data.string("parentId") == null || parentId in nodeDescendants(node.id)) fail(422, "STRUCTURE_CYCLE", "Movimento inválido na árvore")
    checkNodeParent(store.get("node_type", node.data.string("typeId")!!, condominiumId()), parentId)
    checkSiblingLabel(parentId, node.data.string("label")!!, node.id)
    val updated = platformUpdate(node, node.data.plusFields("parentId" to parentId,
        "sortOrder" to (input["sortOrder"] ?: node.data["sortOrder"])))
    return V1Response(structureNodeView(this, updated))
}
