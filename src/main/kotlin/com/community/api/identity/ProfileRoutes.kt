package com.community.api.identity

import com.community.api.core.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

internal fun Route.profileRoutes(db: Database) {
    route("/api/v1/me") {
        post("/verify") {
            val request = call.receive<VerificationRequest>()
            val token = call.identityBearer()
            val result = db.query { tx -> tx.verifyIdentity(tx.authenticate(token), request.password, call.request.local.remoteHost) }
            call.respond(result.unwrap())
        }
        get {
            val token = call.identityBearer()
            call.respond(db.query { tx ->
                val actor = tx.authenticate(token)
                tx.get("account", actor.userId, actor.tenantId)!!.profile()
