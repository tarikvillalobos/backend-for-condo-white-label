package com.community.api.v1.identity

import com.community.api.core.*
import com.community.api.identity.*
import com.community.api.v1.*
import com.community.api.v1.platform.nodePathView
import kotlinx.serialization.json.*
import java.time.Instant

internal fun invitationHandlers(): Map<String, V1Handler> = mapOf(
    "previewInvitation" to V1Handler { it.previewIdentityInvitation() },
    "acceptInvitation" to V1Handler { it.acceptIdentityInvitation() },
    "linkInvitation" to V1Handler { it.linkIdentityInvitation() },
)

private fun V1Context.identityInvitation(): Record {
    val code = identityPath("code")
    val id = code.take(36)
    val invitation = store.find("invitation", id)
    if (invitation == null || !sameSecret(invitation.data.string("codeHash").orEmpty(), hash(code))) {
        fail(404, "INVITATION_NOT_FOUND", "Convite não encontrado")
    }
    if (invitation.data.string("status") != "pending") fail(404, "INVITATION_USED", "Convite indisponível")
    if (!Instant.parse(invitation.data.string("expiresAt")).isAfter(now)) {
        fail(404, "INVITATION_EXPIRED", "Convite expirado")
    }
    return invitation
}

private fun V1Context.previewIdentityInvitation(): V1Response {
    val invitation = identityInvitation()
    val condo = invitation.locationId?.let { store.get("condominium", it) }
    val node = invitation.data.string("nodeId")?.let { store.get("node", it, invitation.locationId) }
    val organization = invitation.data.string("organizationId")?.let { store.get("organization", it) }
    val name = invitation.data.string("name")
    return V1Response(obj("purpose" to (invitation.data.string("purpose") ?: "first_access"),
        "condominiumName" to (condo?.data?.get("name") ?: organization?.data?.get("name") ?: store.get("brand", brandId).data["name"]), "unitLabel" to node?.data?.get("label"),
        "nodePath" to (node?.let { nodePathView(this, it.id) } ?: JsonArray(emptyList())), "blockLabel" to null,
        "role" to invitation.data["role"], "expiresAt" to invitation.data["expiresAt"],
        "requiresCpf" to (invitation.data.string("cpf") != null || invitation.data.string("cpfHash") != null),
        "maskedName" to name?.let { it.take(1) + "***" }))
}

private fun V1Context.checkInvitationCpf(invitation: Record, cpf: String) {
    val expected = invitation.data.string("cpf")
    val hash = invitation.data.string("cpfHash")
    if (expected != null && expected != cpf || hash != null && hash != hash(cpf)) {
        fail(422, "INVITATION_IDENTITY_MISMATCH", "Os dados não correspondem ao convite")
    }
}

private fun V1Context.acceptIdentityInvitation(): V1Response {
    val invitation = identityInvitation()
    if (invitation.data.string("purpose") == "link_membership") {
        fail(409, "EXISTING_ACCOUNT_REQUIRED", "Entre em sua conta para vincular este convite")
    }
    if (!identityRate("v1-invitation", invitation.id)) return rateLimited()
    val cpf = checkedCpf(identityInput("cpf"))
    checkInvitationCpf(invitation, cpf)
    val email = (input.string("email") ?: invitation.data.string("email"))?.let(::checkedEmail)
    val phone = (input.string("phone") ?: invitation.data.string("phone"))?.let(::checkedPhone)
    if (email == null && phone == null) fail(422, "CONTACT_REQUIRED", "Informe pelo menos um contato")
    val invitedAccount = invitation.data.string("userId")?.let { tx.get("account", it, tenantId) }
    for ((type, value) in listOf("cpf" to cpf, "email" to email, "phone" to phone)) {
        val existing = value?.let { findIdentity(type, it) }
        if (existing != null && (existing.decode<Account>().active || existing.id != invitedAccount?.id)) {
            fail(409, "ACCOUNT_ALREADY_EXISTS", "Entre em sua conta para continuar")
        }
    }
    val terms = identityInput("acceptedTermsVersion")
    val requiredTerms = store.find("brand", brandId)?.data?.string("termsVersion")
    if (terms.isBlank() || requiredTerms != null && requiredTerms != terms) {
        fail(422, "TERMS_VERSION_REQUIRED", "Aceite a versão vigente dos termos")
    }
    if (invitedAccount != null && invitedAccount.decode<Account>().email.isNotBlank() && invitedAccount.decode<Account>().email != email) {
        fail(422, "INVITATION_CONTACT_MISMATCH", "O contato não corresponde ao convite")
    }
    val accountData = body(Account(email.orEmpty(), normalizedName(identityInput("name")), Passwords.hash(identityInput("password"))))
    val user = if (invitedAccount == null) tx.create("account", tenantId, data = accountData) else tx.update(invitedAccount, accountData)
    indexIdentityAccount(tx, user, cpf, phone)
    val data = profileData(user)
    saveProfile(user, data.with("phone" to phone, "cpfHash" to hash(cpf),
        "privacy" to (data["privacy"] as JsonObject).with("termsVersion" to terms, "consentUpdatedAt" to now.toString())))
    createInvitationMembership(invitation, user)
    return V1Response(issueIdentitySession(user), 201)
}

private fun V1Context.linkIdentityInvitation(): V1Response {
    val invitation = identityInvitation()
    if (invitation.data.string("purpose") != "link_membership") {
        fail(409, "FIRST_ACCESS_REQUIRED", "Este convite é destinado ao primeiro acesso")
    }
    val user = account()
    val cpf = invitation.data.string("cpf")
    val cpfHash = invitation.data.string("cpfHash")
    if (cpf != null && findIdentity("cpf", cpf)?.id != user.id ||
        cpfHash != null && profileData(user).string("cpfHash") != cpfHash) {
        fail(403, "INVITATION_IDENTITY_MISMATCH", "O convite pertence a outra pessoa")
    }
    val membership = createInvitationMembership(invitation, user)
    return V1Response(membershipView(this, membership), 201)
}

private fun V1Context.createInvitationMembership(invitation: Record, user: Record): Record {
    invitation.data.string("assignmentId")?.let { id ->
        val assignment = store.get("staff_assignment", id)
        if (assignment.ownerId != user.id || assignment.data.string("status") != "active") fail(409, "INVITATION_REVOKED", "A atribuição não está disponível")
        store.update(invitation, invitation.data.with("status" to "accepted", "acceptedAt" to now.toString(), "acceptedBy" to user.id))
        return assignment
    }
    val condoId = invitation.locationId!!
    val nodeId = invitation.data.string("nodeId")!!
    val condo = store.get("condominium", condoId)
    val node = store.get("node", nodeId, condoId)
    if (condo.data.string("status") == "inactive" || node.data.string("status") == "inactive") {
        fail(409, "LOCATION_UNAVAILABLE", "O local do convite não está disponível")
    }
    invitation.data.string("membershipId")?.let { id ->
        val pending = store.get("membership", id, condoId)
        if (pending.ownerId != user.id || pending.data.string("status") != "pending") fail(409, "ALREADY_LINKED", "O vínculo não está pendente")
        val active = store.update(pending, pending.data.with("status" to "active"))
        store.update(invitation, invitation.data.with("status" to "accepted", "acceptedAt" to now.toString(), "acceptedBy" to user.id))
        return active
    }
    if (store.list("membership", condoId, user.id).any {
            it.data.string("nodeId") == nodeId && it.data.string("status") == "active"
        }) fail(409, "ALREADY_LINKED", "Este vínculo já existe")
    val membership = store.create("membership", obj("userId" to user.id, "brandId" to brandId,
        "condominiumId" to condoId, "nodeId" to nodeId, "role" to invitation.data["role"],
        "status" to "active", "startedAt" to now.toString(), "permissions" to emptyList<String>()), condoId, user.id)
    store.update(invitation, invitation.data.with("status" to "accepted", "acceptedAt" to now.toString(), "acceptedBy" to user.id))
    return membership
}
