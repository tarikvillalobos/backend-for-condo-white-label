package com.community.api.community

import com.community.api.core.*
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.*
import java.net.URI
import java.time.Instant

internal fun ApplicationCall.locationId() = parameters["locationId"] ?: badRequest("Location is required")
internal fun ApplicationCall.resourceId(name: String = "id") = parameters[name] ?: badRequest("Resource is required")
internal fun text(value: String, field: String, max: Int = 500): String = value.trim().also {
    if (it.isEmpty() || it.length > max || '\u0000' in it) badRequest("$field must contain 1 to $max characters")
}
internal fun instant(value: String, field: String): Instant = try { Instant.parse(value) } catch (_: Exception) {
    badRequest("$field must be an ISO-8601 instant")
}
internal fun url(value: String): String = value.also {
    val parsed = try { URI(it) } catch (_: Exception) { badRequest("Invalid attachment URL") }
    if (it.length > 2048 || parsed.scheme != "https" || parsed.host.isNullOrEmpty() || parsed.userInfo != null)
        badRequest("Attachments require a public HTTPS URL without credentials")
