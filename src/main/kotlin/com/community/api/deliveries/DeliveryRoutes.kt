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
            get {
                val result = db.query { tx -> service.list(tx, call.deliveryContext(tx)) }
                call.respondPage(result)
            }
            post {
                val request = call.receive<ReceivePackage>()
                val key = call.request.headers["Idempotency-Key"] ?: badRequest("Idempotency-Key is required")
                val result = db.query { tx -> service.receive(tx, call.deliveryContext(tx, "packages.receive"), request, key) }
                call.respond(HttpStatusCode.Created, result)
            }
            route("/{id}") {
                get {
                    call.respond(db.query { tx -> service.get(tx, call.deliveryContext(tx), call.deliveryId()) })
                }
                post("/credential") {
                    val request = call.receive<CredentialRequest>()
                    val result = db.query { tx -> service.credential(tx, call.deliveryContext(tx, "packages.read.own"), call.deliveryId(), request.validForMinutes) }
                    call.response.headers.append("Cache-Control", "no-store")
                    call.respond(result)
                }
