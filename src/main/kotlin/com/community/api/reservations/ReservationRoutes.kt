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
