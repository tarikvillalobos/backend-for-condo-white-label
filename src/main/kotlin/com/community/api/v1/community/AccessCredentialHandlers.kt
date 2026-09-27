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
