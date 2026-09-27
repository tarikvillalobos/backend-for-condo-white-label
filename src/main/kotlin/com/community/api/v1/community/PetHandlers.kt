package com.community.api.v1.community

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.LocalDate

internal fun V1Context.pet(row: Record) = view("Pet", row, obj(
    "node" to node(row.data.text("nodeId")), "breed" to row.data["breed"], "birthDate" to row.data["birthDate"],
    "color" to row.data["color"], "microchip" to row.data["microchip"], "photoUrl" to row.data["photoUrl"], "notes" to row.data["notes"],
    "vaccinations" to JsonArray(store.list("vaccination", locationId, filters = mapOf("petId" to row.id)).map { view("Vaccination", it) }),
))
internal fun V1Context.petAlert(row: Record) = view("PetAlert", row, obj(
    "condominiumId" to row.locationId, "reporterNode" to node(row.data.text("nodeId")), "petId" to row.data["petId"],
    "lastSeenAt" to row.data["lastSeenAt"], "lastSeenLocation" to row.data["lastSeenLocation"], "photoUrl" to row.data["photoUrl"],
    "contactPhone" to row.data["contactPhone"], "reporterName" to personName(row.ownerId),
    "reporterUnitLabel" to (node(row.data.text("nodeId")) as? JsonObject)?.get("label"),
    "isMine" to (row.ownerId == principal?.userId && row.data.text("membershipId") == membershipId), "resolvedAt" to row.data["resolvedAt"],
))
private fun V1Context.validatePet(data: JsonObject, currentId: String? = null) {
