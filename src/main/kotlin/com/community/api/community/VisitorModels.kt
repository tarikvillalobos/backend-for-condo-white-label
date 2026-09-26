package com.community.api.community

import com.community.api.core.*
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.Duration
import java.util.Base64

@Serializable
data class VisitorInput(val name: String, val purpose: String, val validFrom: String, val validUntil: String,
    val unitId: String? = null, val singleUse: Boolean = true)
@Serializable
data class VisitorInvite(val content: VisitorInput, val credentialHash: String, val status: String = "expected",
    val checkedInAt: String? = null, val checkedOutAt: String? = null, val visits: Int = 0)
@Serializable
data class VisitorView(val id: String, val content: VisitorInput, val status: String, val checkedInAt: String?, val checkedOutAt: String?, val visits: Int)
@Serializable
data class VisitorCreated(val invitation: VisitorView, val admissionCode: String)
@Serializable
data class VisitorCheckIn(val admissionCode: String)

internal fun VisitorInput.validated(): VisitorInput {
    val from = instant(validFrom, "validFrom")
    val until = instant(validUntil, "validUntil")
    if (!until.isAfter(from) || !until.isAfter(Instant.now()) || Duration.between(from, until) > Duration.ofDays(90))
        badRequest("Invitation requires a future expiration and a window of at most 90 days")
    return copy(name = text(name, "name", 160), purpose = text(purpose, "purpose", 500))
}
internal fun credentialHash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
internal fun visitorCode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also { SecureRandom().nextBytes(it) })
internal fun Record.visitorView(): VisitorView = decode<VisitorInvite>().let { VisitorView(id, it.content, it.status, it.checkedInAt, it.checkedOutAt, it.visits) }
internal fun VisitorInvite.checkIn(code: String, now: Instant = Instant.now()): VisitorInvite {
    if (!MessageDigest.isEqual(credentialHash.toByteArray(), credentialHash(code).toByteArray())) forbidden()
    if (status == "revoked" || status == "checked_in" || (content.singleUse && visits > 0)) conflict("Invitation cannot be used")
    if (now.isBefore(instant(content.validFrom, "validFrom")) || !now.isBefore(instant(content.validUntil, "validUntil"))) conflict("Invitation is outside its validity window")
    return copy(status = "checked_in", checkedInAt = now.toString(), checkedOutAt = null, visits = visits + 1)
}
