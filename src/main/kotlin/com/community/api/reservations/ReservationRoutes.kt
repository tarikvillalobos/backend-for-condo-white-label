package com.community.api.reservations

import com.community.api.core.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Route.reservationRoutes(db: Database) {
    val service = ReservationService()
    route("/api/v1/locations/{locationId}") {
        route("/facilities") {
            get {
                call.respondPage(db.query { tx -> service.facilities(tx, call.reservationContext(tx, "facilities.read")) })
            }
            post {
                val request = call.receive<FacilityData>()
                call.respond(HttpStatusCode.Created, db.query { tx -> service.saveFacility(tx, call.reservationContext(tx, "facilities.manage"), request) })
            }
            put("/{id}") {
                val request = call.receive<FacilityData>()
                call.respond(db.query { tx -> service.saveFacility(tx, call.reservationContext(tx, "facilities.manage"), request, call.reservationId()) })
            }
            get("/{id}/availability") {
                val start = call.request.queryParameters["startsAt"] ?: badRequest("startsAt is required")
                val end = call.request.queryParameters["endsAt"] ?: badRequest("endsAt is required")
                call.respond(db.query { tx -> service.availability(tx, call.reservationContext(tx, "facilities.read"), call.reservationId(), start, end) })
            }
        }
        route("/reservations") {
            get {
                call.respondPage(db.query { tx -> service.list(tx, call.reservationContext(tx)) })
            }
            post {
                val request = call.receive<CreateReservation>()
                val key = call.reservationKey()
                call.respond(HttpStatusCode.Created, db.query { tx -> service.create(tx, call.reservationContext(tx, "reservations.create"), request, key) })
            }
            post("/maintenance") {
                val request = call.receive<CreateReservation>()
                val key = call.reservationKey()
                call.respond(HttpStatusCode.Created, db.query { tx -> service.create(tx, call.reservationContext(tx, "reservations.manage"), request, key, true) })
            }
            get("/{id}") {
                call.respond(db.query { tx -> service.get(tx, call.reservationContext(tx), call.reservationId()) })
            }
            post("/{id}/cancel") {
                val result = db.query { tx ->
                    val actor = call.actor(tx)
                    val ctx = tx.authorizeAny(actor, call.parameters["locationId"] ?: badRequest("Location is required"),
                        setOf("reservations.create", "reservations.manage"), "reservations")
                    service.transition(tx, ctx, call.reservationId(), "cancel")
                }
                call.respond(result)
            }
            post("/{id}/approve") {
                call.respond(db.query { tx -> service.transition(tx, call.reservationContext(tx, "reservations.manage"), call.reservationId(), "approve") })
            }
            post("/{id}/reject") {
                call.respond(db.query { tx -> service.transition(tx, call.reservationContext(tx, "reservations.manage"), call.reservationId(), "reject") })
            }
        }
    }
}

private fun ApplicationCall.reservationId(): String = parameters["id"] ?: badRequest("Resource ID is required")
private fun ApplicationCall.reservationKey(): String = request.headers["Idempotency-Key"] ?: badRequest("Idempotency-Key is required")

private fun ApplicationCall.reservationContext(tx: Tx, permission: String? = null): Context {
    val locationId = parameters["locationId"] ?: badRequest("Location is required")
    val actor = actor(tx)
    return if (permission == null) tx.authorizeAny(actor, locationId,
        setOf("reservations.read.own", "reservations.read.all"), "reservations")
    else tx.authorize(actor, locationId, permission, "reservations")
}
