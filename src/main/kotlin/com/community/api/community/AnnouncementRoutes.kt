package com.community.api.community

import com.community.api.core.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class AnnouncementInput(val title: String, val message: String, val unitId: String? = null,
    val publishAt: String? = null, val expiresAt: String? = null, val pinned: Boolean = false,
    val priority: String = "normal", val attachments: List<String> = emptyList(), val acknowledgmentRequired: Boolean = false)
@Serializable
data class Announcement(val content: AnnouncementInput, val archived: Boolean = false)
@Serializable
data class Acknowledgment(val resourceId: String, val acknowledgedAt: String = Instant.now().toString())

internal fun AnnouncementInput.validated(): AnnouncementInput {
