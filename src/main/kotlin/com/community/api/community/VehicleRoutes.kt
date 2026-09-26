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
            })
        }
        put("/{id}") {
            val input = call.receive<VehicleInput>().validated()
            call.respond(db.query { tx ->
                val ctx = tx.authorizeAny(call.actor(tx), call.locationId(), setOf("vehicles.manage.own", "vehicles.manage"), "vehicles")
                val row = tx.record(ctx, "vehicle", call.resourceId())
                tx.own(ctx, row, "vehicles.manage")
                tx.requireUnit(ctx, input.unitId, "vehicles.manage")
                if (tx.list("vehicle", ctx.tenantId, ctx.locationId).any { it.id != row.id && it.decode<VehicleInput>().plate == input.plate }) conflict("Vehicle is already registered")
                tx.changed(ctx, row, body(input), "vehicle.updated")
            })
        }
        delete("/{id}") {
            db.query { tx ->
                val ctx = tx.authorizeAny(call.actor(tx), call.locationId(), setOf("vehicles.manage.own", "vehicles.manage"), "vehicles")
                val row = tx.record(ctx, "vehicle", call.resourceId())
                tx.own(ctx, row, "vehicles.manage")
                if (tx.list("parking", ctx.tenantId, ctx.locationId).any { it.decode<ParkingInput>().vehicleId == row.id }) conflict("Release the parking space before deleting this vehicle")
                tx.delete(row)
                tx.audit(ctx, "vehicle.deleted", row.id)
            }
            call.respond(HttpStatusCode.NoContent)
        }
        post("/{id}/movements") {
            val input = call.receive<VehicleMovementInput>()
            if (input.direction !in setOf("entry", "exit")) badRequest("Direction must be entry or exit")
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "vehicles.manage", "vehicles")
                val row = tx.record(ctx, "vehicle", call.resourceId())
                val vehicle = row.decode<VehicleInput>()
                if (input.direction == "entry") tx.requireMember(ctx.tenantId, call.locationId(), row.ownerId ?: notFound())
                if (input.direction == "entry" && vehicle.validUntil?.let { !instant(it, "validUntil").isAfter(Instant.now()) } == true) conflict("Vehicle authorization has expired")
                val previous = tx.list("vehicle_movement", ctx.tenantId, ctx.locationId)
                    .filter { it.decode<VehicleMovement>().vehicleId == row.id }.maxByOrNull { it.createdAt }
                if (previous?.decode<VehicleMovement>()?.direction == input.direction || (previous == null && input.direction == "exit")) conflict("Invalid movement sequence")
                tx.saved(ctx, "vehicle_movement", body(VehicleMovement(row.id, input.direction)), row.ownerId)
            })
        }
        get("/{id}/movements") {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorizeAny(call.actor(tx), call.locationId(), setOf("vehicles.read.own", "vehicles.read.all"), "vehicles")
                val row = tx.record(ctx, "vehicle", call.resourceId())
                tx.own(ctx, row, "vehicles.read.all")
                tx.list("vehicle_movement", ctx.tenantId, ctx.locationId).filter { it.decode<VehicleMovement>().vehicleId == row.id }
            })
        }
    }
    route("/parking") {
        get {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorizeAny(call.actor(tx), call.locationId(), setOf("vehicles.read.own", "vehicles.read.all"), "vehicles")
                val vehicles = tx.visible(ctx, "vehicle", "vehicles.read.all").map { it.id }.toSet()
                tx.list("parking", ctx.tenantId, ctx.locationId).filter { ctx.can("vehicles.read.all") || it.decode<ParkingInput>().vehicleId in vehicles }
            })
        }
        post {
            val input = call.receive<ParkingInput>()
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "vehicles.manage", "vehicles")
                val content = input.copy(name = text(input.name, "name", 100))
                input.vehicleId?.let { tx.record(ctx, "vehicle", it) }
                if (tx.list("parking", ctx.tenantId, ctx.locationId).any { it.decode<ParkingInput>().name == content.name }) conflict("Parking space already exists")
                tx.saved(ctx, "parking", body(content))
            })
        }
        put("/{id}") {
            val input = call.receive<ParkingInput>()
            call.respond(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "vehicles.manage", "vehicles")
                val row = tx.record(ctx, "parking", call.resourceId())
                input.vehicleId?.let { tx.record(ctx, "vehicle", it) }
                val content = input.copy(name = text(input.name, "name", 100))
                if (tx.list("parking", ctx.tenantId, ctx.locationId).any { it.id != row.id && it.decode<ParkingInput>().name == content.name }) conflict("Parking space already exists")
                tx.changed(ctx, row, body(content), "parking.assigned")
            })
        }
    }
}
