package com.community.api.v1.community

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.time.Instant

private val credentialRandom = SecureRandom()
internal fun V1Context.newAccessSecret(): JsonObject {
    repeat(32) {
        val code = credentialRandom.nextInt(1_000_000).toString().padStart(6, '0')
        val hash = Secrets.sign("$tenantId|${location()}|$code")
        if (store.list("access_invite", locationId, filters = mapOf("codeHash" to hash)).any { inviteStatus(it) in setOf("active", "scheduled") }) return@repeat
        val qr = Secrets.token()
        return obj("codeHash" to hash, "codeCiphertext" to seal(code), "qrHash" to Secrets.hash(qr), "qrCiphertext" to seal(qr))
    }
    fail(503, "CREDENTIAL_CAPACITY_REACHED", "Não foi possível reservar um código; tente novamente")
}
internal fun V1Context.accessCredential(row: Record): V1Response {
    if (inviteStatus(row) !in setOf("scheduled", "active")) fail(409, "INVITE_NOT_ACTIVE", "Convite sem credencial disponível")
    audit("access_invite.credential_read", row)
    return V1Response(obj("inviteId" to row.id, "code" to unseal(row.data.text("codeCiphertext")!!),
        "qrPayload" to "visit:${row.id}:${unseal(row.data.text("qrCiphertext")!!)}",
        "expiresAt" to row.data["validUntil"], "revalidateAfter" to now.plusSeconds(30).toString()))
}
private fun V1Context.validationResult(row: Record?, reason: String?, consumed: Boolean = false): V1Response {
    val visitor = row?.data?.get("visitorSnapshot") as? JsonObject
    val node = row?.data?.text("nodeId")?.let { node(it) }
    return V1Response(obj("valid" to (reason == null), "kind" to if (row != null) "visit" else null, "reason" to reason,
        "parcelId" to null, "compartmentCode" to null, "inviteId" to row?.id, "visitorName" to visitor?.get("name"),
        "unitLabel" to (node as? JsonObject)?.get("label"), "node" to node, "consumedNow" to consumed))
}
internal fun V1Context.validateAccess(): V1Response {
    val code = input.text("code")
    val qr = input.text("qrPayload")
    if ((code == null) == (qr == null)) fail(422, "CREDENTIAL_REQUIRED", "Informe code ou qrPayload")
    val gateId = input.text("gateId") ?: fail(422, "GATE_REQUIRED", "Portão obrigatório")
    val gate = store.get("gate", gateId, locationId)
    if (!gate.data.flag("active", true)) return validationResult(null, "wrong_gate")
    val device = principal?.deviceId
    if (device != null && gate.data.text("deviceId") != device) fail(403, "DEVICE_GATE_MISMATCH", "Dispositivo não autorizado neste portão")
    val attemptId = Secrets.hash("$tenantId|${location()}|${device ?: principal?.userId}|access")
    val attempt = store.find("access_attempt", attemptId, locationId)
    val inWindow = attempt?.data?.text("startedAt")?.let { timestamp(it).plusSeconds(300).isAfter(now) } == true
    if (inWindow && attempt!!.data.number("count") >= 10) fail(429, "RATE_LIMITED", "Muitas tentativas; tente novamente em cinco minutos")
    val row = if (code != null) store.list("access_invite", locationId, filters = mapOf("codeHash" to Secrets.sign("$tenantId|${location()}|$code"))).firstOrNull {
        inviteStatus(it) in setOf("active", "scheduled", "used")
    } else {
        val parts = qr!!.split(':')
        if (parts.size != 3 || parts[0] != "visit") null else store.find("access_invite", parts[1], locationId)?.takeIf { it.data.text("qrHash") == Secrets.hash(parts[2]) }
    }
    if (row == null) {
        val data = obj("startedAt" to if (inWindow) attempt!!.data["startedAt"] else JsonPrimitive(now.toString()), "count" to if (inWindow) attempt!!.data.number("count") + 1 else 1)
        if (attempt == null) store.create("access_attempt", data, locationId, id = attemptId) else store.update(attempt, data)
        return validationResult(null, "unknown")
    }
    val status = inviteStatus(row)
    val exit = input.text("direction") == "exit"
    if (status == "revoked" || status == "expired" || status == "scheduled" || (status == "used" && !exit)) return validationResult(row, if (status == "used") "consumed" else status)
