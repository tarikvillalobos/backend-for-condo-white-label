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
                delete("/credential") {
                    call.respond(db.query { tx -> service.revokeCredential(tx, call.deliveryContext(tx, "packages.read.own"), call.deliveryId()) })
                }
                post("/delegates") {
                    val request = call.receive<DelegationRequest>()
                    call.respond(db.query { tx -> service.delegate(tx, call.deliveryContext(tx, "packages.read.own"), call.deliveryId(), request.userId) })
                }
                delete("/delegates/{userId}") {
                    val userId = call.parameters["userId"] ?: badRequest("Delegate is required")
                    call.respond(db.query { tx -> service.delegate(tx, call.deliveryContext(tx, "packages.read.own"), call.deliveryId(), userId, true) })
                }
                post("/report-pickup") {
                    call.respond(db.query { tx -> service.reportPickup(tx, call.deliveryContext(tx, "packages.read.own"), call.deliveryId()) })
                }
                post("/remind") {
                    call.respond(db.query { tx -> service.remind(tx, call.deliveryContext(tx, "packages.receive"), call.deliveryId()) })
                }
                post("/confirm-pickup") {
                    val request = call.receive<ConfirmPickup>()
                    call.respond(db.query { tx -> service.confirmPickup(tx, call.deliveryContext(tx, "packages.collect"), call.deliveryId(), request) })
                }
                post("/cancel") {
                    call.respond(db.query { tx -> service.cancel(tx, call.deliveryContext(tx, "packages.manage"), call.deliveryId()) })
                }
            }
        }
        route("/lockers") {
            get {
                call.respondPage(db.query { tx ->
                    val ctx = tx.authorizeAny(call.actor(tx), call.parameters["locationId"]!!, setOf("lockers.read", "lockers.manage"), "packages")
                    service.lockers(tx, ctx)
                })
            }
            post {
                val request = call.receive<LockerData>()
                call.respond(HttpStatusCode.Created, db.query { tx -> service.saveLocker(tx, call.deliveryContext(tx, "lockers.manage"), request) })
            }
            put("/{id}") {
                val request = call.receive<LockerData>()
                call.respond(db.query { tx -> service.saveLocker(tx, call.deliveryContext(tx, "lockers.manage"), request, call.deliveryId()) })
