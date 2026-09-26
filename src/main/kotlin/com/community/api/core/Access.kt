package com.community.api.core

import com.community.api.identity.authenticate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

val allFeatures = setOf(
    "packages", "reservations", "announcements", "events", "pets", "requests", "visitors",
    "vehicles", "maintenance", "documents", "cameras", "notifications", "contacts",
)

@Serializable
data class Membership(
