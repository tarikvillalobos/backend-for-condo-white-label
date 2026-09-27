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
    "unlinkOrganizationCondominium" to V1Handler { it.unlinkOrganizationCondominium() },
    "listOrganizationStaff" to V1Handler { c -> V1Response(obj("items" to c.store.list("staff_assignment",
        filters = mapOf("organizationId" to c.pathId("organizationId"))).map { c.assignmentView(it) })) },
    "createOrganizationStaff" to V1Handler { c ->
        c.store.get("organization", c.pathId("organizationId"))
        V1Response(c.assignmentView(c.createPlatformStaff(c.pathId("organizationId"))), 201)
    },
) + customRoleHandlers()

private fun V1Context.organizationView(record: Record): JsonObject = project("Organization", record.document().plusFields(
    "condominiumsCount" to store.list("organization_condominium", filters = mapOf("organizationId" to record.id, "status" to "active")).size,
    "staffCount" to store.list("staff_assignment", filters = mapOf("organizationId" to record.id, "status" to "active")).size))

private fun V1Context.createOrganization(): V1Response {
    requireBrandAdministrator()
    val invite = input["adminInvite"] as? JsonObject
    val userId = input.string("adminUserId")
    if (invite != null && userId != null) fail(422, "INVALID_ADMIN_IDENTITY", "Informe adminUserId ou adminInvite")
    val record = store.create("organization", JsonObject(obj("document" to null, "contacts" to emptySupport(), "active" to true) +
        (input - setOf("adminInvite", "adminUserId"))))
