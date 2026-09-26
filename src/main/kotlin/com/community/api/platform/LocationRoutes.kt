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
                val ctx = if (existing.decode<Location>().active) tx.authorize(actor, id, "locations.manage")
                    else tx.authorize(actor, null, "locations.manage").copy(locationId = id)
                tx.update(existing, body(input)).also { tx.audit(ctx, "location.updated", it.id) }
            })
        }
        route("/{locationId}/units") {
            get {
                call.respondPage(db.query { tx ->
                    val locationId = call.parameters["locationId"]!!
                    val actor = call.actor(tx)
                    val ctx = tx.authorize(actor, locationId, "locations.read")
                    val units = tx.list("unit", ctx.tenantId, locationId)
                    if (ctx.can("units.manage")) units else {
                        val allowed = tx.activeMemberships(ctx.tenantId, ctx.userId).mapNotNull { it.decode<Membership>().unitId }.toSet()
                        units.filter { it.id in allowed }
                    }
                })
            }
            post {
                val input = call.receive<UnitData>()
                input.validate()
                call.respond(HttpStatusCode.Created, db.query { tx ->
                    val locationId = call.parameters["locationId"]!!
                    val ctx = tx.authorize(call.actor(tx), locationId, "units.manage")
                    if (tx.requireRecord("location", locationId, ctx.tenantId).decode<Location>().kind != "condominium") badRequest("Standalone locations do not have residential units")
                    if (tx.list("unit", ctx.tenantId, locationId).any { it.decode<UnitData>().let { unit -> unit.name == input.name && unit.building == input.building } }) conflict("Unit already exists")
                    tx.create("unit", ctx.tenantId, locationId, data = body(input)).also { tx.audit(ctx, "unit.created", it.id) }
                })
            }
            put("/{id}") {
                val input = call.receive<UnitData>().also { it.validate() }
                call.respond(db.query { tx ->
                    val locationId = call.parameters["locationId"]!!
                    val ctx = tx.authorize(call.actor(tx), locationId, "units.manage")
                    val unit = tx.requireRecord("unit", call.parameters["id"]!!, ctx.tenantId, locationId)
                    if (tx.list("unit", ctx.tenantId, locationId).any { it.id != unit.id && it.decode<UnitData>().let { old -> old.name == input.name && old.building == input.building } }) conflict("Unit already exists")
                    tx.update(unit, body(input)).also { tx.audit(ctx, "unit.updated", it.id) }
                })
            }
        }
    }
}

private fun UnitData.validate() {
    name.validText("unit name", 80)
    building?.validText("building", 80)
    floor?.validText("floor", 80)
}
