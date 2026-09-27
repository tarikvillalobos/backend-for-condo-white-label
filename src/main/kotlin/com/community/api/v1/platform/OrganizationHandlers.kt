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
    if (invite != null || userId != null) withInput(obj("userId" to userId, "invite" to invite,
        "role" to "org_admin", "mfaRequired" to true)).createPlatformStaff(record.id)
    return V1Response(organizationView(record), 201)
}

private fun V1Context.organizationCondominiumView(record: Record): JsonObject = obj(
    "condominium" to condominiumView(store.get("condominium", record.locationId!!)),
    "services" to record.data["services"], "startedAt" to record.data["startedAt"], "endedAt" to record.data["endedAt"])

private fun V1Context.checkCondominiumLinkAuthority(condoId: String) {
    if (brandAdministrator()) return
    if (store.list("staff_assignment", condoId, userId).none { it.data.string("status") == "active" &&
            it.data.string("role") == "condo_admin" }) fail(403, "CONDOMINIUM_ADMIN_REQUIRED", "O vínculo exige autorização do condomínio")
}

private fun V1Context.linkOrganizationCondominium(): V1Response {
    val org = store.get("organization", pathId("organizationId"))
    val condo = store.get("condominium", required("condominiumId"))
    checkCondominiumLinkAuthority(condo.id)
    if (!org.data.bool("active", true) || !condo.data.bool("active", true)) fail(409, "INACTIVE_RESOURCE", "Organização ou condomínio inativo")
    if (store.list("organization_condominium", condo.id, filters = mapOf("organizationId" to org.id, "status" to "active")).isNotEmpty()) {
        fail(409, "ALREADY_LINKED", "O condomínio já está vinculado")
    }
    val record = store.create("organization_condominium", obj("organizationId" to org.id, "condominiumId" to condo.id,
        "services" to input["services"], "status" to "active", "startedAt" to now.toString(), "endedAt" to null), condo.id)
    return V1Response(organizationCondominiumView(record), 201)
}

private fun V1Context.unlinkOrganizationCondominium(): V1Response {
    val condoId = pathId("condominiumId")
    val orgId = pathId("organizationId")
    if (!brandAdministrator() && !managesCondominium(condoId)) fail(403, "ACCESS_DENIED", "Condomínio fora do escopo")
    store.list("organization_condominium", condoId, filters = mapOf("organizationId" to orgId, "status" to "active"))
        .forEach { store.update(it, it.data.plusFields("status" to "ended", "endedAt" to now.toString())) }
    store.list("staff_assignment", condoId, filters = mapOf("organizationId" to orgId, "status" to "active"))
        .forEach { store.update(it, it.data.plusFields("status" to "ended", "endedAt" to now.toString())); tx.revokeSessions(tenantId, it.ownerId!!) }
    return V1Response(status = 204)
}

internal fun V1Context.issueOrganizationStaffInvitation(account: Record, assignment: Record, organizationId: String) {
    if (MailConfig.fromEnvironment() == null) fail(503, "CHANNEL_UNAVAILABLE", "E-mail não está configurado")
    val id = UUID.randomUUID().toString()
    val code = "${id}_${Secrets.token()}"
    store.create("invitation", obj("brandId" to brandId, "nodeId" to null, "role" to assignment.data["role"],
        "organizationId" to organizationId, "userId" to account.id, "assignmentId" to assignment.id,
        "name" to account.data["name"], "email" to account.data["email"], "codeHash" to hash(code), "purpose" to "first_access",
        "status" to "pending", "expiresAt" to now.plusSeconds(7 * 86400).toString(), "acceptedAt" to null), ownerId = account.id, id = id)
    enqueueMail(account.data.string("email")!!, "Community: convite de equipe", "Seu código de convite é:\n$code\nExpira em sete dias.")
}
