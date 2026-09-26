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
