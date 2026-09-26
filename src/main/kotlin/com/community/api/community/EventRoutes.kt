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
    return copy(title = text(title, "title", 160), description = text(description, "description", 10000))
}
internal fun Route.eventRoutes(db: Database) {
    route("/events") {
        get {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "events.read", "events")
                tx.list("event", ctx.tenantId, ctx.locationId)
            })
        }
        post {
            val input = call.receive<EventInput>().validated()
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "events.manage", "events")
                tx.saved(ctx, "event", body(CommunityEvent(input)))
            })
        }
        put("/{id}") {
            val input = call.receive<EventInput>().validated()
            call.respond(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "events.manage", "events")
                val row = tx.record(ctx, "event", call.resourceId())
                if (row.decode<CommunityEvent>().cancelled) conflict("Cancelled events cannot be edited")
                val attendees = tx.list("attendance", ctx.tenantId, ctx.locationId).count { it.decode<Attendance>().eventId == row.id }
                if (input.capacity < attendees) conflict("Capacity cannot be lower than confirmed attendance")
                tx.changed(ctx, row, body(CommunityEvent(input)), "event.updated")
            })
        }
        post("/{id}/cancel") {
            call.respond(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "events.manage", "events")
                val row = tx.record(ctx, "event", call.resourceId())
                tx.changed(ctx, row, body(row.decode<CommunityEvent>().copy(cancelled = true)), "event.cancelled")
            })
        }
        post("/{id}/attendance") {
            call.respond(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "events.attend", "events")
                val row = tx.record(ctx, "event", call.resourceId())
                val event = row.decode<CommunityEvent>()
