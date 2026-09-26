package com.community.api.community

import com.community.api.core.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

internal fun Route.requestRoutes(db: Database) {
    route("/requests") {
        get {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorizeAny(call.actor(tx), call.locationId(), setOf("requests.read.own", "requests.read.all"), "requests")
                tx.visible(ctx, "request", "requests.read.all")
            })
        }
        post {
            val input = call.receive<RequestInput>().validated()
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "requests.create", "requests")
