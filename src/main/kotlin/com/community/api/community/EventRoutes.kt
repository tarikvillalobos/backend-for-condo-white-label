package com.community.api.community

import com.community.api.core.*
import com.community.api.reservations.ReservationData
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class EventInput(val title: String, val description: String, val startsAt: String, val endsAt: String,
    val capacity: Int = 100, val reservationId: String? = null)
@Serializable
data class CommunityEvent(val content: EventInput, val cancelled: Boolean = false)
@Serializable
data class Attendance(val eventId: String)

internal fun EventInput.validated(): EventInput {
    if (!instant(endsAt, "endsAt").isAfter(instant(startsAt, "startsAt"))) badRequest("End must follow start")
    if (capacity !in 1..10000) badRequest("Capacity must be between 1 and 10000")
    return copy(title = text(title, "title", 160), description = text(description, "description", 10000))
}

internal fun Tx.validateEventReservation(ctx: Context, input: EventInput, eventId: String? = null) {
    val reservationId = input.reservationId ?: return
    val locationId = ctx.locationId ?: forbidden()
    authorize(ctx.actor, locationId, "events.manage", "reservations")
    val row = requireRecord("reservation", reservationId, ctx.tenantId, locationId)
    if (row.ownerId != ctx.userId && !ctx.can("reservations.manage")) forbidden()
    val reservation = row.decode<ReservationData>()
    if (reservation.status !in setOf("PENDING", "CONFIRMED")) conflict("Event requires an active reservation")
    if (instant(input.startsAt, "startsAt").isBefore(instant(reservation.request.startsAt, "reservation.startsAt")) ||
        instant(input.endsAt, "endsAt").isAfter(instant(reservation.request.endsAt, "reservation.endsAt"))) {
        badRequest("Event must fit inside the linked reservation interval")
    }
    if (list("event", ctx.tenantId, locationId).any { existing ->
            existing.id != eventId && existing.decode<CommunityEvent>().let {
                !it.cancelled && it.content.reservationId == reservationId
            }
        }) conflict("Reservation is already linked to another active event")
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
                tx.validateEventReservation(ctx, input)
                tx.saved(ctx, "event", body(CommunityEvent(input)))
            })
        }
        put("/{id}") {
            val input = call.receive<EventInput>().validated()
            call.respond(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "events.manage", "events")
                val row = tx.record(ctx, "event", call.resourceId())
                if (row.decode<CommunityEvent>().cancelled) conflict("Cancelled events cannot be edited")
                tx.validateEventReservation(ctx, input, row.id)
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
                if (event.cancelled || !instant(event.content.startsAt, "startsAt").isAfter(Instant.now())) conflict("Registration is closed")
                val attendees = tx.list("attendance", ctx.tenantId, ctx.locationId).filter { it.decode<Attendance>().eventId == row.id }
                attendees.firstOrNull { it.ownerId == ctx.userId } ?: run {
                    if (attendees.size >= event.content.capacity) conflict("Event has reached capacity")
                    tx.saved(ctx, "attendance", body(Attendance(row.id)))
                }
            })
        }
        delete("/{id}/attendance") {
            db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "events.attend", "events")
                tx.record(ctx, "event", call.resourceId())
                tx.list("attendance", ctx.tenantId, ctx.locationId, ctx.userId)
                    .filter { it.decode<Attendance>().eventId == call.resourceId() }.forEach { tx.delete(it) }
                tx.audit(ctx, "event.attendance.cancelled", call.resourceId())
            }
            call.respond(HttpStatusCode.NoContent)
        }
        get("/{id}/attendance") {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "events.read", "events")
                tx.record(ctx, "event", call.resourceId())
                tx.visible(ctx, "attendance", "events.manage").filter { it.decode<Attendance>().eventId == call.resourceId() }
            })
        }
    }
}
