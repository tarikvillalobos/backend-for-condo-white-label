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
            })
        }
        patch {
            val request = call.receive<ProfileRequest>()
            val name = normalizedName(request.name)
            val token = call.identityBearer()
            call.respond(db.query { tx ->
                val actor = tx.authenticate(token)
                val account = tx.get("account", actor.userId, actor.tenantId)!!
                tx.update(account, body(account.decode<Account>().copy(name = name))).profile()
            })
        }
        post("/password") {
            val request = call.receive<PasswordRequest>()
            Passwords.validate(request.newPassword)
            val token = call.identityBearer()
            val result = db.query { tx -> tx.changePassword(tx.authenticate(token), request, call.request.local.remoteHost) }
            call.respond(result.unwrap())
        }
        post("/contact/request") {
            val request = call.receive<ContactRequest>()
            val token = call.identityBearer()
            val result = db.query { tx -> tx.requestContactChange(tx.authenticate(token), request, call.request.local.remoteHost) }
            call.respond(result.unwrap())
        }
        post("/contact/confirm") {
            val request = call.receive<ContactConfirmation>()
            val token = call.identityBearer()
            val result = db.query { tx -> tx.confirmContactChange(tx.authenticate(token), request.token) }
            call.respond(result.unwrap())
        }
    }
}
