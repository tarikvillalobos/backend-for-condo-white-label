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
                tx.saved(ctx, "request", body(ResidentRequest(input)))
            })
        }
        get("/{id}") {
            call.respond(db.query { tx ->
                val ctx = tx.authorizeAny(call.actor(tx), call.locationId(), setOf("requests.read.own", "requests.read.all"), "requests")
                tx.record(ctx, "request", call.resourceId()).also { tx.own(ctx, it, "requests.read.all") }
            })
        }
        post("/{id}/assign") {
            val input = call.receive<RequestAssignment>()
            input.dueAt?.let { instant(it, "dueAt") }
            call.respond(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "requests.manage", "requests")
                tx.requireMember(ctx.tenantId, call.locationId(), input.userId)
                tx.authorize(Actor(input.userId, ctx.tenantId, "assignment"), call.locationId(), "requests.manage", "requests")
                val row = tx.record(ctx, "request", call.resourceId())
                val value = row.decode<ResidentRequest>()
                if (value.status in setOf("closed", "cancelled")) conflict("Closed requests cannot be assigned")
                tx.notify(ctx.tenantId, ctx.locationId, input.userId, "Request assigned", "A request was assigned to you")
