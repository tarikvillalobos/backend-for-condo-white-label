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
