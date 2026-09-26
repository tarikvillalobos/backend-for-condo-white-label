package com.community.api.platform

import com.community.api.core.*
import com.community.api.identity.requireRecentAuthentication
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.serialization.json.jsonPrimitive

fun Route.reportRoutes(db: Database) {
    route("/api/v1/locations/{locationId}") {
        get("/audit") {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.parameters["locationId"]!!, "audit.read")
                tx.list("audit", ctx.tenantId, ctx.locationId)
            })
        }
        get("/reports") {
