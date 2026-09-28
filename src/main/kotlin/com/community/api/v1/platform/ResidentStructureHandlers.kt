package com.community.api.v1.platform

import com.community.api.core.*
import com.community.api.identity.revokeSessions
import com.community.api.v1.*
import com.community.api.v1.identity.profileData
import kotlinx.serialization.json.*

internal fun residentStructureHandlers(): Map<String, V1Handler> = mapOf(
    "getCondominium" to V1Handler { c ->
        val condo = c.store.get("condominium", c.condominiumId())
        V1Response(c.project("CondominiumInfo", condo.document().plusFields("modules" to modulesView(c))))
    },
    "getStructure" to V1Handler { c ->
        val node = c.residentNode(c.unitId ?: c.fail(404, "UNIT_NOT_FOUND", "Vínculo sem unidade"))
        V1Response(structureNodeView(c, node, c.query["depth"]?.toIntOrNull() ?: 2))
    },
    "getStructureNode" to V1Handler { c -> V1Response(structureNodeView(c, c.residentNode(c.pathId("nodeId")),
        c.query["depth"]?.toIntOrNull() ?: 1)) },
    "searchStructure" to V1Handler { c ->
