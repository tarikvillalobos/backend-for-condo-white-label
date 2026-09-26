package com.community.api.identity

import com.community.api.core.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Route.identityRoutes(db: Database) {
    route("/api/v1/auth") {
        post("/login") {
            val request = call.receive<LoginRequest>()
            val result = db.query { it.login(request, call.request.local.remoteHost) }
            call.respond(result.unwrap())
        }
        post("/refresh") {
            val request = call.receive<RefreshRequest>()
            val result = db.query { it.refresh(request.refreshToken, call.request.local.remoteHost) }
            call.respond(result.unwrap())
        }
