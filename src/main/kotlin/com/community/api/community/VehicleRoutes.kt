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
