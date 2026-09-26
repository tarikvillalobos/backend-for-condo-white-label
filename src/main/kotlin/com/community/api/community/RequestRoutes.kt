package com.community.api.community

import com.community.api.core.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

internal fun Route.requestRoutes(db: Database) {
    route("/requests") {
        get {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorizeAny(call.actor(tx), call.locationId(), setOf("requests.read.own", "requests.read.all"), "requests")
                tx.visible(ctx, "request", "requests.read.all")
            })
        }
        post {
            val input = call.receive<RequestInput>().validated()
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "requests.create", "requests")
                tx.saved(ctx, "request", body(ResidentRequest(input)))
            })
        }
        get("/{id}") {
            call.respond(db.query { tx ->
                val ctx = tx.authorizeAny(call.actor(tx), call.locationId(), setOf("requests.read.own", "requests.read.all"), "requests")
                tx.record(ctx, "request", call.resourceId()).also { tx.own(ctx, it, "requests.read.all") }
            })
        }
        post("/{id}/assign") {
            val input = call.receive<RequestAssignment>()
            input.dueAt?.let { instant(it, "dueAt") }
            call.respond(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "requests.manage", "requests")
                tx.requireMember(ctx.tenantId, call.locationId(), input.userId)
                tx.authorize(Actor(input.userId, ctx.tenantId, "assignment"), call.locationId(), "requests.manage", "requests")
                val row = tx.record(ctx, "request", call.resourceId())
                val value = row.decode<ResidentRequest>()
                if (value.status in setOf("closed", "cancelled")) conflict("Closed requests cannot be assigned")
                tx.notify(ctx.tenantId, ctx.locationId, input.userId, "Request assigned", "A request was assigned to you")
                tx.changed(ctx, row, body(value.copy(assignedTo = input.userId, dueAt = input.dueAt)), "request.assigned")
            })
        }
        post("/{id}/status") {
            val input = call.receive<RequestTransition>()
            val reason = text(input.reason, "reason", 2000)
            call.respond(db.query { tx ->
                val ctx = tx.authorizeAny(call.actor(tx), call.locationId(), setOf("requests.create", "requests.manage"), "requests")
                val row = tx.record(ctx, "request", call.resourceId())
                tx.own(ctx, row, "requests.manage")
                val value = row.decode<ResidentRequest>()
                validateRequestTransition(value.status, input.status, ctx.can("requests.manage"))
                tx.saved(ctx, "request_comment", body(RequestComment(row.id, "${value.status} → ${input.status}: $reason", false, emptyList())))
                row.ownerId?.let { owner ->
                    if (tx.activeMemberships(ctx.tenantId, owner).any { it.locationId == null || it.locationId == ctx.locationId })
                        tx.notify(ctx.tenantId, ctx.locationId, owner, "Request updated", "Your request status has changed")
                }
                tx.changed(ctx, row, body(value.copy(status = input.status)), "request.status.changed")
            })
        }
        get("/{id}/comments") {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorizeAny(call.actor(tx), call.locationId(), setOf("requests.read.own", "requests.read.all"), "requests")
                val row = tx.record(ctx, "request", call.resourceId())
                tx.own(ctx, row, "requests.read.all")
                tx.list("request_comment", ctx.tenantId, ctx.locationId).filter {
                    val comment = it.decode<RequestComment>()
                    comment.requestId == row.id && (!comment.internal || ctx.can("requests.manage"))
                }
            })
        }
        post("/{id}/comments") {
            val input = call.receive<RequestCommentInput>()
            val message = text(input.message, "message", 10000)
            if (input.attachments.size > 10) badRequest("At most 10 attachments are allowed")
            val attachments = input.attachments.map(::url)
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorizeAny(call.actor(tx), call.locationId(), setOf("requests.comment", "requests.manage"), "requests")
                val row = tx.record(ctx, "request", call.resourceId())
                tx.own(ctx, row, "requests.manage")
