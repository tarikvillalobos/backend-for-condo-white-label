package com.community.api.v1.platform

import com.community.api.core.*
import com.community.api.identity.MailConfig
import com.community.api.identity.revokeSessions
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.util.UUID

internal fun organizationHandlers(): Map<String, V1Handler> = mapOf(
    "listOrganizations" to V1Handler { c -> V1Response(c.page("organization", transform = { c.organizationView(it) })) },
    "createOrganization" to V1Handler { it.createOrganization() },
    "getOrganization" to V1Handler { c -> V1Response(c.organizationView(c.store.get("organization", c.pathId("organizationId")))) },
    "updateOrganization" to V1Handler { c ->
        val record = c.store.get("organization", c.pathId("organizationId"))
        V1Response(c.organizationView(c.platformUpdate(record, JsonObject(record.data + c.input))))
    },
    "listOrganizationCondominiums" to V1Handler { c -> V1Response(c.page("organization_condominium",
        filters = mapOf("organizationId" to c.pathId("organizationId"), "status" to "active"), transform = { c.organizationCondominiumView(it) })) },
    "linkOrganizationCondominium" to V1Handler { it.linkOrganizationCondominium() },
