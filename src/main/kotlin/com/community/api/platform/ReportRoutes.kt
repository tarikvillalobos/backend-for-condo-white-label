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
            call.respond(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.parameters["locationId"]!!, "reports.read")
                val records = reportKinds.filter { (_, policy) ->
                    runCatching { tx.authorize(ctx.actor, ctx.locationId, policy.first, policy.second) }.isSuccess
                }.mapValues { (kind, _) -> tx.list(kind, ctx.tenantId, ctx.locationId) }
                Report(records.mapValues { it.value.size }, records.mapValues { entry ->
                    entry.value.groupingBy { it.data["status"]?.jsonPrimitive?.content ?: "unspecified" }.eachCount()
                })
            })
        }
        get("/reports/{kind}/export") {
            val kind = call.parameters["kind"]!!
            val policy = reportKinds[kind] ?: notFound()
            val csv = db.query { tx ->
                val actor = call.actor(tx)
                val ctx = tx.authorize(actor, call.parameters["locationId"]!!, "reports.read")
                tx.authorize(actor, ctx.locationId, policy.first, policy.second)
                tx.requireRecentAuthentication(actor)
                val rows = tx.list(kind, ctx.tenantId, ctx.locationId)
                tx.audit(ctx, "report.exported.$kind", ctx.locationId!!)
