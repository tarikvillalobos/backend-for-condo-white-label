package com.community.api.platform

import com.community.api.core.*
import com.community.api.identity.Account
import com.community.api.identity.InvitationIssue
import com.community.api.identity.issueInvitation
import java.time.Instant
import java.time.ZoneId

fun ClientSettings.validate() {
    name.validText("client name")
    if (!allFeatures.containsAll(features)) badRequest("Unknown feature")
    if (!passwordLogin && !otpLogin) badRequest("Enable at least one login method")
    supportEmail?.let { if (it.length > 254 || !it.contains('@')) badRequest("Invalid support email") }
}

fun Location.validate() {
    name.validText("location name")
    if (kind !in setOf("condominium", "standalone")) badRequest("Invalid location kind")
    if (runCatching { ZoneId.of(timeZone) }.isFailure) badRequest("Invalid time zone")
    if (!allFeatures.containsAll(features)) badRequest("Unknown feature")
    if (address != null && address.length > 1000) badRequest("Address is too long")
    if (operatingRules != null && operatingRules.length > 5000) badRequest("Operating rules are too long")
    if (petRules.maxPetsPerUnit != null && petRules.maxPetsPerUnit !in 1..100) badRequest("Invalid pet limit")
    if (petRules.allowedSpecies.size > 30 || petRules.allowedSpecies.any { it.isBlank() || it.length > 60 }) badRequest("Invalid species policy")
}

fun Brand.validate() {
    name.validText("brand name")
    if (application !in setOf("smartlocker", "condo")) badRequest("Invalid application")
    if (!Regex("^#[0-9A-Fa-f]{6}$").matches(primaryColor)) badRequest("Invalid color")
    if (logoUrl != null && (logoUrl.length > 2048 || !logoUrl.startsWith("https://"))) badRequest("Logo must use HTTPS")
    if (!allFeatures.containsAll(features)) badRequest("Unknown feature")
}

fun Tx.validateMembership(context: Context, membership: Membership, allowInactiveAccount: Boolean = false) {
    if (membership.relationship != null && membership.relationship !in setOf("owner", "tenant", "dependent", "household")) badRequest("Invalid unit relationship")
    if (membership.relationship != null && membership.unitId == null) badRequest("Unit relationship requires a unit")
    if (membership.locationId != context.locationId) forbidden()
    if (membership.role == "client_admin" && membership.locationId != null) badRequest("Client administrators require client scope")
    if (membership.role == "client_admin" && membership.expiresAt != null) badRequest("Client administrator memberships cannot expire")
    if (membership.locationId == null && membership.role != "client_admin") badRequest("Operational roles require a location")
    if (membership.expiresAt != null) {
        val expires = runCatching { Instant.parse(membership.expiresAt) }.getOrElse { badRequest("Invalid expiration") }
        if (!expires.isAfter(Instant.now())) badRequest("Expiration must be in the future")
    }
    if (membership.role !in roleTemplates && list("role", context.tenantId).none { it.decode<RoleDefinition>().name == membership.role }) {
        badRequest("Unknown role")
    }
    val granted = memberPermissions(context.tenantId, membership)
    if (granted.any { !context.can(it) }) forbidden()
    val account = requireRecord("account", membership.userId, context.tenantId).decode<Account>()
    if (!allowInactiveAccount && !account.active) badRequest("Account is inactive")
    if (membership.locationId != null) requireRecord("location", membership.locationId, context.tenantId)
    if (membership.unitId != null) {
        val locationId = membership.locationId ?: badRequest("Unit requires a location")
        requireRecord("unit", membership.unitId, context.tenantId, locationId)
    }
}

fun Tx.saveMembership(context: Context, membership: Membership, existing: Record? = null, allowInactive: Boolean = false): Record {
    validateMembership(context, membership, allowInactive)
    if (existing != null && (existing.tenantId != context.tenantId || existing.locationId != context.locationId || existing.ownerId != membership.userId)) forbidden()
    val duplicates = list("membership", context.tenantId, ownerId = membership.userId)
        .filter { it.id != existing?.id && it.locationId == membership.locationId && it.decode<Membership>().unitId == membership.unitId }
    if (duplicates.isNotEmpty()) conflict("Membership already exists")
    if (existing != null) protectLastAdministrator(existing, membership)
    val result = if (existing == null) create("membership", context.tenantId, membership.locationId, membership.userId, body(membership))
        else update(existing, body(membership))
    audit(context, if (existing == null) "membership.created" else "membership.updated", result.id)
    return result
}

private fun Tx.protectLastAdministrator(existing: Record, next: Membership) {
    val current = existing.decode<Membership>()
    if (current.role != "client_admin" || (next.role == "client_admin" && next.current())) return
    val alternatives = list("membership", existing.tenantId).any {
        val membership = it.decode<Membership>()
        it.id != existing.id && membership.role == "client_admin" && membership.current() &&
            get("account", membership.userId, existing.tenantId)?.decode<Account>()?.active == true
    }
    if (!alternatives) conflict("The client must retain an active administrator")
}

fun Tx.invite(context: Context, request: InvitationRequest): InvitationIssue {
    if (request.locationId != context.locationId) forbidden()
    // Create pending account and membership in the same transaction as the invitation.
    val invitation = issueInvitation(context.tenantId, request.email, request.name)
    val membership = Membership(invitation.userId, request.locationId, request.unitId, request.role, expiresAt = request.expiresAt, relationship = request.relationship)
    val previous = list("membership", context.tenantId, ownerId = invitation.userId)
        .firstOrNull { it.locationId == request.locationId && it.decode<Membership>().unitId == request.unitId }
    saveMembership(context, membership, previous, allowInactive = true)
    audit(context, "invitation.issued", invitation.userId)
    return invitation
}
