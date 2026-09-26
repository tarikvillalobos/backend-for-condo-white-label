package com.community.api.community

import com.community.api.core.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

internal fun Route.maintenanceRoutes(db: Database) {
    route("/staff") {
        get {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "staff.manage", "maintenance")
                tx.list("staff", ctx.tenantId, ctx.locationId)
            })
        }
        post {
            val input = call.receive<StaffProfile>()
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "staff.manage", "maintenance")
