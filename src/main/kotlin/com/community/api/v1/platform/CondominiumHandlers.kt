package com.community.api.v1.platform

import com.community.api.core.*
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.ZoneId

fun platformHandlers(): Map<String, V1Handler> = condominiumHandlers() + structureHandlers() +
    membershipAdminHandlers() + staffAdminHandlers() + organizationHandlers() + brandAdminHandlers()

private fun condominiumHandlers(): Map<String, V1Handler> = mapOf(
    "createCondominium" to V1Handler { it.createCondominium() },
    "listManagedCondominiums" to V1Handler { c -> V1Response(c.page("condominium",
        predicate = { c.managesCondominium(it.id) }, transform = { c.condominiumView(it) })) },
    "adminGetCondominium" to V1Handler { c ->
        val record = c.store.get("condominium", c.condominiumId())
        V1Response(c.condominiumView(record), headers = mapOf("ETag" to "\"${record.version}\""))
    },
    "adminUpdateCondominium" to V1Handler { it.updateCondominium() },
    "updateModules" to V1Handler { it.updateCondominiumModules() },
