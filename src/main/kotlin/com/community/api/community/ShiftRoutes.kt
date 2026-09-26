package com.community.api.community

import com.community.api.core.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class ShiftNoteInput(val message: String, val incident: Boolean = false)

internal fun Route.shiftRoutes(db: Database) {
    route("/shift-notes") {
        get {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "concierge.notes", "visitors")
                tx.list("shift_note", ctx.tenantId, ctx.locationId).sortedByDescending { it.createdAt }
            })
        }
