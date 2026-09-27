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
