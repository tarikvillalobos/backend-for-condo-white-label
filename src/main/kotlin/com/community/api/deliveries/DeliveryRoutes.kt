package com.community.api.deliveries

import com.community.api.core.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Route.deliveryRoutes(db: Database) {
    val service = DeliveryService()
    route("/api/v1/locations/{locationId}") {
        post("/locker-events") {
            val request = call.receive<LockerPickupEvent>()
            call.respond(db.query { tx ->
                val locationId = call.parameters["locationId"] ?: badRequest("Location is required")
                service.trustedPickup(tx, call.integration(tx, locationId, "locker"), request)
            })
        }
        route("/packages") {
