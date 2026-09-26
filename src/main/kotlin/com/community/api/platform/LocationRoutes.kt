package com.community.api.platform

import com.community.api.core.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Route.locationRoutes(db: Database) {
    route("/api/v1/locations") {
        get {
            call.respondPage(db.query { tx ->
                val actor = call.actor(tx)
                val memberships = tx.activeMemberships(actor.tenantId, actor.userId)
                val allLocations = memberships.any { it.locationId == null }
                tx.list("location", actor.tenantId).filter {
                    it.decode<Location>().active && (allLocations || memberships.any { member -> member.locationId == it.id })
                }
            })
        }
        post {
            val input = call.receive<Location>().also { it.validate() }
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), null, "locations.manage")
                tx.create("location", ctx.tenantId, data = body(input)).also { tx.audit(ctx, "location.created", it.id) }
            })
        }
        get("/{locationId}") {
            call.respond(db.query { tx ->
                val id = call.parameters["locationId"]!!
                val ctx = tx.authorize(call.actor(tx), id, "locations.read")
                tx.requireRecord("location", id, ctx.tenantId)
            })
        }
        put("/{locationId}") {
            val input = call.receive<Location>().also { it.validate() }
            call.respond(db.query { tx ->
                val id = call.parameters["locationId"]!!
                val actor = call.actor(tx)
                val existing = tx.requireRecord("location", id, actor.tenantId)
