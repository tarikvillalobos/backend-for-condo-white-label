package com.community.api.community

import com.community.api.core.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

internal fun Route.maintenanceRoutes(db: Database) {
    route("/staff") {
        get {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "staff.manage", "maintenance")
                tx.list("staff", ctx.tenantId, ctx.locationId)
            })
        }
        post {
            val input = call.receive<StaffProfile>()
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "staff.manage", "maintenance")
                tx.requireMember(ctx.tenantId, call.locationId(), input.userId)
                val content = input.copy(responsibility = text(input.responsibility, "responsibility", 1000))
                val existing = tx.list("staff", ctx.tenantId, ctx.locationId).firstOrNull { it.decode<StaffProfile>().userId == input.userId }
                if (existing == null) tx.saved(ctx, "staff", body(content), input.userId)
                else tx.changed(ctx, existing, body(content), "staff.updated")
            })
        }
    }
    route("/contractors") {
        get {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "maintenance.manage", "maintenance")
                tx.list("contractor", ctx.tenantId, ctx.locationId)
            })
        }
        post {
            val input = call.receive<ContractorInput>().validated()
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "maintenance.manage", "maintenance")
                tx.saved(ctx, "contractor", body(input))
            })
        }
        put("/{id}") {
            val input = call.receive<ContractorInput>().validated()
            call.respond(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "maintenance.manage", "maintenance")
                tx.changed(ctx, tx.record(ctx, "contractor", call.resourceId()), body(input), "contractor.updated")
            })
        }
    }
    route("/work-orders") {
        get {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorizeAny(call.actor(tx), call.locationId(), setOf("maintenance.read", "maintenance.manage"), "maintenance")
                tx.list("work_order", ctx.tenantId, ctx.locationId).filter { ctx.can("maintenance.manage") || it.decode<WorkOrder>().content.assignedTo == ctx.userId }
            })
        }
        post {
            val input = call.receive<WorkOrderInput>()
            instant(input.scheduledAt, "scheduledAt")
