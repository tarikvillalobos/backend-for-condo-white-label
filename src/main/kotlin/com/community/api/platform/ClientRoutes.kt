package com.community.api.platform

import com.community.api.core.*
import com.community.api.identity.requireRecentAuthentication
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Route.platformRoutes(db: Database) {
    clientRoutes(db)
    locationRoutes(db)
    membershipRoutes(db)
    reportRoutes(db)
    attachmentRoutes(db)
}

private fun Route.clientRoutes(db: Database) {
    route("/api/v1") {
        get("/configuration") {
            call.respond(db.query { tx ->
                val actor = call.actor(tx)
                val client = tx.requireRecord("client", actor.tenantId, actor.tenantId)
                val memberships = tx.activeMemberships(actor.tenantId, actor.userId)
                if (memberships.isEmpty()) forbidden()
                Configuration(client, tx.list("brand", actor.tenantId), memberships)
            })
        }
        put("/client") {
            val input = call.receive<ClientSettings>().also { it.validate() }
            if (!input.active) badRequest("Use the operator client-state command to suspend a client")
            call.respond(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), null, "client.manage")
                tx.requireRecentAuthentication(ctx.actor)
                val client = tx.requireRecord("client", ctx.tenantId, ctx.tenantId)
                val updated = tx.update(client, body(input))
                tx.audit(ctx, "client.updated", client.id)
                updated
            })
        }
