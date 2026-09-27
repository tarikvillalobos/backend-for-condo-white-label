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
