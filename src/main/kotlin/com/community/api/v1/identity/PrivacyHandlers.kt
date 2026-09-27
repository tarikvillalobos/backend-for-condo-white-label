package com.community.api.v1.identity

import com.community.api.core.*
import com.community.api.identity.requireRecentAuthentication
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.Instant

internal fun privacyHandlers(): Map<String, V1Handler> = mapOf(
    "getPrivacy" to V1Handler { c -> V1Response(c.profileData(c.account())["privacy"]!!) },
    "updatePrivacy" to V1Handler { it.updateIdentityPrivacy() },
    "createDataRequest" to V1Handler { it.createIdentityDataRequest() },
    "listDataRequests" to V1Handler { c -> V1Response(obj("items" to c.store.list("data_request", ownerId = c.userId)
        .map { c.project("DataRequest", it.document()) })) },
)

private fun V1Context.updateIdentityPrivacy(): V1Response {
    val user = account()
    val data = profileData(user)
    val privacy = JsonObject((data["privacy"] as JsonObject) + input).with("consentUpdatedAt" to now.toString())
    saveProfile(user, data.with("privacy" to privacy))
    return V1Response(privacy)
}

private fun V1Context.createIdentityDataRequest(): V1Response {
    val kind = identityInput("kind")
    if (kind == "deletion") {
        tx.requireRecentAuthentication(principal!!.actor!!)
        val otpAt = store.get("session", principal.sessionId!!).data.string("otpVerifiedAt")?.let(Instant::parse)
        if (otpAt == null || otpAt.isBefore(now.minusSeconds(600))) {
            fail(403, "OTP_VERIFICATION_REQUIRED", "Confirme um código em /me/verify antes de solicitar exclusão")
        }
    }
    if (store.list("data_request", ownerId = userId).any {
            it.data.string("kind") == kind && it.data.string("status") in setOf("received", "processing")
        }) fail(409, "DATA_REQUEST_PENDING", "Já existe uma solicitação pendente")
    val graceDays = (store.find("brand", brandId)?.data?.get("deletionGraceDays") as? JsonPrimitive)?.intOrNull?.coerceIn(1, 90) ?: 7
    val requested = store.create("data_request", obj("kind" to kind, "status" to "received",
        "requestedAt" to now.toString(), "completedAt" to null, "downloadUrl" to null,
        "downloadExpiresAt" to null, "executeAfter" to if (kind == "deletion") now.plusSeconds(graceDays.toLong() * 86400).toString() else null),
        ownerId = userId)
    if (kind == "deletion") return V1Response(project("DataRequest", requested.document()), 202)
    val kinds = listOf("membership", "vehicle", "pet", "vaccination", "visitor", "access_invite",
        "reservation", "attendance", "ticket", "comment", "occurrence", "notification", "parcel", "data_request")
    val data = obj("exportedAt" to now.toString(), "profile" to identityProfile(),
        "privacy" to profileData(account())["privacy"], "records" to kinds.associateWith { type ->
            store.list(type, ownerId = userId).map { scrubIdentityExport(it.document()) }
        })
    val file = writePrivateFile(this, "personal-data.json", "application/json", data.toString().toByteArray(Charsets.UTF_8))
    val ready = store.update(requested, requested.data.with("status" to "ready", "completedAt" to now.toString(),
        "downloadUrl" to fileUrl(file.id), "downloadExpiresAt" to now.plusSeconds(900).toString(), "fileId" to file.id))
    return V1Response(project("DataRequest", ready.document()), 202)
}

private fun scrubIdentityExport(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.filterKeys { key ->
        val normalized = key.lowercase()
        listOf("password", "secret", "token", "credential", "codehash", "keyhash", "encrypted").none(normalized::contains)
    }.mapValues { scrubIdentityExport(it.value) })
    is JsonArray -> JsonArray(element.map(::scrubIdentityExport))
    else -> element
}
