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
        get("/brands") {
            call.respondPage(db.query { tx ->
                val actor = call.actor(tx)
                if (tx.activeMemberships(actor.tenantId, actor.userId).isEmpty()) forbidden()
                tx.list("brand", actor.tenantId)
            })
        }
        post("/brands") {
            val input = call.receive<Brand>().also { it.validate() }
            val record = db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), null, "brands.manage")
                tx.create("brand", ctx.tenantId, data = body(input)).also { tx.audit(ctx, "brand.created", it.id) }
            }
            call.respond(HttpStatusCode.Created, record)
        }
        put("/brands/{id}") {
            val input = call.receive<Brand>().also { it.validate() }
            call.respond(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), null, "brands.manage")
                val record = tx.requireRecord("brand", call.parameters["id"]!!, ctx.tenantId)
                tx.update(record, body(input)).also { tx.audit(ctx, "brand.updated", it.id) }
            })
        }
        get("/roles") {
            call.respond(db.query { tx ->
                val actor = call.actor(tx)
                tx.authorize(actor, null, "roles.manage")
                roleTemplates.map { RoleDefinition(it.key, it.value) } + tx.list("role", actor.tenantId).map { it.decode<RoleDefinition>() }
            })
        }
        post("/roles") {
            val input = call.receive<RoleDefinition>()
            input.name.validText("role name", 80)
            if (input.permissions.size > 100 || input.permissions.any { it.length > 80 }) badRequest("Invalid permissions")
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), null, "roles.manage")
                tx.requireRecentAuthentication(ctx.actor)
                if (input.permissions.any { !ctx.can(it) }) forbidden()
                if (input.name in roleTemplates || tx.list("role", ctx.tenantId).any { it.decode<RoleDefinition>().name == input.name }) conflict("Role name already exists")
                tx.create("role", ctx.tenantId, data = body(input)).also { tx.audit(ctx, "role.created", it.id) }
            })
        }
        put("/roles/{id}") {
            val input = call.receive<RoleDefinition>()
            call.respond(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), null, "roles.manage")
                tx.requireRecentAuthentication(ctx.actor)
                val record = tx.requireRecord("role", call.parameters["id"]!!, ctx.tenantId)
                if (input.name != record.decode<RoleDefinition>().name) badRequest("Role names cannot be changed")
                if (input.permissions.size > 100 || input.permissions.any { it.length > 80 || !ctx.can(it) }) forbidden()
                tx.update(record, body(input)).also { tx.audit(ctx, "role.updated", it.id) }
            })
        }
    }
}

@kotlinx.serialization.Serializable
private data class Configuration(val client: Record, val brands: List<Record>, val memberships: List<Record>)
