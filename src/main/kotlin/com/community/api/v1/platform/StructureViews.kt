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
