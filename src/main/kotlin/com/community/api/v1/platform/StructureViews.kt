package com.community.api.v1.platform

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*

internal fun V1Context.seedDefaultNodeTypes(condominiumId: String): List<Record> {
    val codes = listOf("root", "tower", "block", "wing", "floor", "unit", "room", "parking", "common_area")
    return codes.mapIndexed { index, code ->
        val parents = when (code) {
            "root" -> emptyList()
            "tower", "block" -> listOf("root")
            "wing" -> listOf("root", "tower", "block")
            "floor" -> listOf("root", "tower", "block", "wing")
            else -> listOf("root", "tower", "block", "wing", "floor")
        }
        store.create("node_type", obj("code" to code, "name" to code, "allowedParents" to parents,
            "addressable" to (code in setOf("unit", "room")), "reservable" to (code in setOf("parking", "common_area")),
            "sortOrder" to index), condominiumId)
    }
}

fun structureNodeView(c: V1Context, record: Record, depth: Int = 0): JsonObject {
    val type = c.store.get("node_type", record.data.string("typeId")!!, record.locationId)
    val path = nodePathView(c, record.id)
    val children = c.store.list("node", record.locationId, filters = mapOf("parentId" to record.id))
        .filter { c.query["includeInactive"] == "true" || it.data.bool("active", true) }
        .sortedWith(compareBy({ it.data["sortOrder"]?.jsonPrimitive?.intOrNull ?: 0 }, { it.data.string("label") }))
    return c.project("StructureNode", record.document().plusFields("type" to c.project("NodeType", type.document()),
        "path" to path, "depth" to (path.size - 1), "childrenCount" to children.size,
        "children" to if (depth > 0) children.map { structureNodeView(c, it, depth - 1) } else emptyList<JsonObject>()))
}

fun nodePathView(c: V1Context, id: String): JsonArray {
    val result = mutableListOf<JsonElement>()
    val seen = mutableSetOf<String>()
    var current: String? = id
    while (current != null) {
        if (!seen.add(current) || seen.size > 64) c.fail(409, "STRUCTURE_CYCLE", "A estrutura contém um ciclo")
        val node = c.store.get("node", current)
