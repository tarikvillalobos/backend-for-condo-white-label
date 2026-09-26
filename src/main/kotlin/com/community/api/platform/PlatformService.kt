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
}

fun Brand.validate() {
    name.validText("brand name")
    if (application !in setOf("smartlocker", "condo")) badRequest("Invalid application")
    if (!Regex("^#[0-9A-Fa-f]{6}$").matches(primaryColor)) badRequest("Invalid color")
    if (logoUrl != null && (logoUrl.length > 2048 || !logoUrl.startsWith("https://"))) badRequest("Logo must use HTTPS")
    if (!allFeatures.containsAll(features)) badRequest("Unknown feature")
}

fun Tx.validateMembership(context: Context, membership: Membership, allowInactiveAccount: Boolean = false) {
    if (membership.locationId != context.locationId) forbidden()
    if (membership.role == "client_admin" && membership.locationId != null) badRequest("Client administrators require client scope")
    if (membership.role == "client_admin" && membership.expiresAt != null) badRequest("Client administrator memberships cannot expire")
    if (membership.locationId == null && membership.role != "client_admin") badRequest("Operational roles require a location")
    if (membership.expiresAt != null) {
        val expires = runCatching { Instant.parse(membership.expiresAt) }.getOrElse { badRequest("Invalid expiration") }
        if (!expires.isAfter(Instant.now())) badRequest("Expiration must be in the future")
