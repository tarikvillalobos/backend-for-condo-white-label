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
