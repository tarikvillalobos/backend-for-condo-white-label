package com.community.api.platform

import com.community.api.core.*
import com.community.api.identity.Account
import com.community.api.identity.requireRecentAuthentication
import com.community.api.identity.revokeAccountCredentials
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

fun Route.membershipRoutes(db: Database) {
    membershipScope(db, "/api/v1")
    membershipScope(db, "/api/v1/locations/{locationId}")
    route("/api/v1/accounts") {
        get {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), null, "accounts.manage")
                tx.list("account", ctx.tenantId).map { record ->
                    val account = record.decode<Account>()
                    AccountSummary(record.id, account.email, account.name, account.active)
                }
            })
        }
        put("/{id}/state") {
            val input = call.receive<AccountState>()
            call.respond(db.query { tx ->
                val actor = call.actor(tx)
                val ctx = tx.authorize(actor, null, "accounts.manage")
                tx.requireRecentAuthentication(actor)
                val account = tx.requireRecord("account", call.parameters["id"]!!, ctx.tenantId)
                if (!input.active && account.id == ctx.userId) badRequest("Use another administrator to deactivate your account")
                val updated = tx.update(account, body(account.decode<Account>().copy(active = input.active)))
                if (!input.active) tx.revokeAccountCredentials(ctx.tenantId, account.id)
                tx.audit(ctx, "account.state_changed", account.id)
                val data = updated.decode<Account>()
                AccountSummary(updated.id, data.email, data.name, data.active)
            })
        }
    }
}

private fun Route.membershipScope(db: Database, path: String) {
    route(path) {
        get("/memberships") {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.parameters["locationId"], "memberships.read")
                tx.list("membership", ctx.tenantId, ctx.locationId)
                    .filter { ctx.locationId != null || it.locationId == null }
            })
        }
        post("/memberships") {
            val input = call.receive<Membership>()
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.parameters["locationId"], "memberships.manage")
                tx.requireRecentAuthentication(ctx.actor)
                tx.saveMembership(ctx, input)
            })
        }
        put("/memberships/{id}") {
            val input = call.receive<Membership>()
            call.respond(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.parameters["locationId"], "memberships.manage")
                tx.requireRecentAuthentication(ctx.actor)
                val previous = tx.requireRecord("membership", call.parameters["id"]!!, ctx.tenantId, ctx.locationId)
                tx.saveMembership(ctx, input, previous)
            })
        }
        post("/invitations") {
            val input = call.receive<InvitationRequest>()
            val key = call.request.headers["Idempotency-Key"]?.validText("Idempotency-Key", 128)
                ?: badRequest("Idempotency-Key is required")
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.parameters["locationId"], "memberships.manage")
                tx.requireRecentAuthentication(ctx.actor)
                val scope = "invitation:${ctx.userId}:${ctx.locationId}:$key"
                val digest = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
                val id = "invite-$digest"
                if (tx.get("idempotency", id, ctx.tenantId) != null) conflict("Invitation already issued for this key; credentials are returned once")
                val invitation = tx.invite(ctx, input)
                tx.create("idempotency", ctx.tenantId, ctx.locationId, ctx.userId, buildJsonObject {
                    put("operation", "invitation")
                    put("resourceId", invitation.userId)
                }, id = id)
                invitation
            })
        }
    }
}
