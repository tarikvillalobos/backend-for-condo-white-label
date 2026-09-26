package com.community.api.community

import com.community.api.core.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import java.time.Instant

internal fun Route.visitorRoutes(db: Database) {
    route("/visitors") {
        get {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorizeAny(call.actor(tx), call.locationId(), setOf("visitors.read.own", "visitors.read.all"), "visitors")
                tx.visible(ctx, "visitor", "visitors.read.all").map { it.visitorView() }
            })
        }
        post {
            val input = call.receive<VisitorInput>().validated()
            val key = call.request.headers["Idempotency-Key"] ?: badRequest("Idempotency-Key is required")
            if (!key.matches(Regex("[A-Za-z0-9_.:-]{8,128}"))) badRequest("Invalid Idempotency-Key")
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "visitors.create", "visitors")
                tx.requireUnit(ctx, input.unitId, "visitors.manage")
                val operationId = credentialHash("${ctx.tenantId}|${ctx.locationId}|${ctx.userId}|$key")
                if (tx.get("visitor_operation", operationId, ctx.tenantId) != null) conflict("Invitation already created for this Idempotency-Key; admission credentials are returned only once")
                val code = visitorCode()
                val row = tx.saved(ctx, "visitor", body(VisitorInvite(input, credentialHash(code))))
                tx.create("visitor_operation", ctx.tenantId, ctx.locationId, ctx.userId, body(Acknowledgment(row.id)), operationId)
                VisitorCreated(row.visitorView(), code)
            })
        }
        post("/{id}/revoke") {
            call.respond(db.query { tx ->
                val ctx = tx.authorizeAny(call.actor(tx), call.locationId(), setOf("visitors.create", "visitors.manage"), "visitors")
                val row = tx.record(ctx, "visitor", call.resourceId())
                tx.own(ctx, row, "visitors.manage")
                val invite = row.decode<VisitorInvite>()
                if (invite.status == "checked_in") conflict("Check out the visitor before revoking the invitation")
                tx.changed(ctx, row, body(invite.copy(status = "revoked")), "visitor.revoked").visitorView()
