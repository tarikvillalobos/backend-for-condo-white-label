package com.community.api.community

import com.community.api.core.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class EventInput(val title: String, val description: String, val startsAt: String, val endsAt: String, val capacity: Int = 100)
@Serializable
data class CommunityEvent(val content: EventInput, val cancelled: Boolean = false)
@Serializable
data class Attendance(val eventId: String)

internal fun EventInput.validated(): EventInput {
    if (!instant(endsAt, "endsAt").isAfter(instant(startsAt, "startsAt"))) badRequest("End must follow start")
    if (capacity !in 1..10000) badRequest("Capacity must be between 1 and 10000")
