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
