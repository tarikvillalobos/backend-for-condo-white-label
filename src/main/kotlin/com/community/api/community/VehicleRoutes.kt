package com.community.api.community

import com.community.api.core.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class VehicleInput(val plate: String, val model: String, val color: String, val unitId: String? = null, val validUntil: String? = null)
@Serializable
data class ParkingInput(val name: String, val vehicleId: String? = null)
@Serializable
data class VehicleMovementInput(val direction: String)
@Serializable
data class VehicleMovement(val vehicleId: String, val direction: String, val recordedAt: String = Instant.now().toString())

internal fun VehicleInput.validated(): VehicleInput {
    val normalizedPlate = plate.uppercase().filterNot { it == ' ' || it == '-' }
    if (!normalizedPlate.matches(Regex("[A-Z0-9]{3,12}"))) badRequest("Invalid vehicle plate")
    validUntil?.let { if (!instant(it, "validUntil").isAfter(Instant.now())) badRequest("Vehicle authorization has expired") }
    return copy(plate = normalizedPlate, model = text(model, "model", 100), color = text(color, "color", 50))
}
internal fun Route.vehicleRoutes(db: Database) {
    route("/vehicles") {
        get {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorizeAny(call.actor(tx), call.locationId(), setOf("vehicles.read.own", "vehicles.read.all"), "vehicles")
                tx.visible(ctx, "vehicle", "vehicles.read.all")
            })
        }
        post {
            val input = call.receive<VehicleInput>().validated()
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "vehicles.create", "vehicles")
                tx.requireUnit(ctx, input.unitId, "vehicles.manage")
                if (tx.list("vehicle", ctx.tenantId, ctx.locationId).any { it.decode<VehicleInput>().plate == input.plate }) conflict("Vehicle is already registered")
                tx.saved(ctx, "vehicle", body(input))
