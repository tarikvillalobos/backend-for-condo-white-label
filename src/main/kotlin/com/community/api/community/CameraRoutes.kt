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
