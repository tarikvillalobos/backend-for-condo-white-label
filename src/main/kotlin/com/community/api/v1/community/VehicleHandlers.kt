package com.community.api.v1.community

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*

internal fun V1Context.vehicle(row: Record) = view("Vehicle", row, obj(
    "node" to node(row.data.text("nodeId")), "color" to row.data["color"],
    "parkingSpot" to row.data["parkingSpot"], "tag" to row.data["tag"],
))
internal fun vehicleHandlers(): Map<String, V1Handler> = mapOf(
    "listVehicles" to V1Handler { c -> c.listResponse("vehicle", true) { c.vehicle(it) } },
    "createVehicle" to V1Handler { c ->
        if (c.store.list("vehicle", c.locationId, filters = mapOf("plate" to c.input.text("plate")!!)).isNotEmpty())
            c.fail(409, "VEHICLE_ALREADY_EXISTS", "Placa já cadastrada neste condomínio")
        val row = c.save("vehicle", c.input.merge(obj("nodeId" to c.checkedNode(required = true))))
        V1Response(c.vehicle(row), 201)
    },
    "updateVehicle" to V1Handler { c -> V1Response(c.vehicle(c.change(c.record("vehicle", "vehicleId"), c.input))) },
    "deleteVehicle" to V1Handler { c -> c.remove(c.record("vehicle", "vehicleId")) },
    "adminListVehicles" to V1Handler { c -> c.listResponse("vehicle") { row ->
        obj("vehicle" to c.vehicle(row), "node" to c.node(row.data.text("nodeId")), "ownerName" to c.personName(row.ownerId))
    } },
    "vehicleMovements" to V1Handler { c -> c.vehicleMovements() },
    "adminVehicleMovements" to V1Handler { c -> c.vehicleMovements() },
)
private fun V1Context.vehicleMovements(): V1Response {
    val vehicle = record("vehicle", "vehicleId")
    return listResponse("access_event", filters = mapOf("vehiclePlate" to vehicle.data.text("plate")!!)) { view("AccessEvent", it) }
}
