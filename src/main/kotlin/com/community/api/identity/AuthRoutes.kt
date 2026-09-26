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
        post("/activate") {
            val request = call.receive<ActivationRequest>()
            Passwords.validate(request.password)
            val result = db.query { it.activate(request, call.request.local.remoteHost, "activation") }
            call.respond(result.unwrap())
        }
        post("/recovery/request") {
            val request = call.receive<EmailRequest>()
            val result = db.query { it.requestRecovery(request, call.request.local.remoteHost, false) }
            call.respond(result.unwrap())
        }
        post("/recovery/confirm") {
            val request = call.receive<ActivationRequest>()
            Passwords.validate(request.password)
            val result = db.query { it.activate(request, call.request.local.remoteHost, "recovery") }
            call.respond(result.unwrap())
        }
        post("/otp/request") {
            val request = call.receive<EmailRequest>()
            val result = db.query { it.requestRecovery(request, call.request.local.remoteHost, true) }
            call.respond(result.unwrap())
        }
        post("/otp/confirm") {
            val request = call.receive<OtpRequest>()
            val result = db.query { it.consumeOtp(request, call.request.local.remoteHost) }
            call.respond(result.unwrap())
        }
        post("/logout") {
            val token = call.identityBearer()
            db.query { tx ->
                val actor = tx.authenticate(token)
                val session = tx.get("session", actor.sessionId, actor.tenantId)!!
                tx.update(session, body(session.decode<SessionData>().copy(revoked = true)))
                tx.identityAudit(actor.tenantId, actor.userId, "session.logged_out")
            }
            call.respond(Accepted())
        }
    }
    profileRoutes(db)
    sessionRoutes(db)
}

internal fun ApplicationCall.identityBearer(): String {
    val header = request.headers["Authorization"].orEmpty()
    if (!header.startsWith("Bearer ", ignoreCase = true)) throw ApiException(401, "unauthorized", "Authentication required")
    return header.substring(7).trim().takeIf { it.isNotEmpty() }
        ?: throw ApiException(401, "unauthorized", "Authentication required")
}
