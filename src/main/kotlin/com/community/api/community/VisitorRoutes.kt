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
