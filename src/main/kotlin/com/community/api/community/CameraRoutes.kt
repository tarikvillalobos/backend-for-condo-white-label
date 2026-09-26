package com.community.api.community

import com.community.api.core.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class CameraInput(val name: String, val area: String, val enabled: Boolean = true, val unitId: String? = null)

private fun CameraInput.validated() = copy(name = text(name, "name", 160), area = text(area, "area", 300))
internal fun Route.cameraRoutes(db: Database) {
    route("/cameras") {
        get {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "cameras.view", "cameras")
                tx.list("camera", ctx.tenantId, ctx.locationId).filter {
                    val camera = it.decode<CameraInput>()
                    (camera.enabled || ctx.can("cameras.manage")) && tx.audience(ctx, camera.unitId, "cameras.manage")
                }
            })
        }
        post {
            val input = call.receive<CameraInput>().validated()
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "cameras.manage", "cameras")
                tx.requireUnit(ctx, input.unitId, "cameras.manage")
                tx.saved(ctx, "camera", body(input))
            })
        }
        put("/{id}") {
            val input = call.receive<CameraInput>().validated()
            call.respond(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "cameras.manage", "cameras")
                tx.requireUnit(ctx, input.unitId, "cameras.manage")
                tx.changed(ctx, tx.record(ctx, "camera", call.resourceId()), body(input), "camera.updated")
            })
        }
