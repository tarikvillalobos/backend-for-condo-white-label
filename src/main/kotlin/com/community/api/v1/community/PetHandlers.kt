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
    val nodeId = checkedNode(data, true)
    data.text("birthDate")?.let { if (LocalDate.parse(it).isAfter(LocalDate.now())) fail(422, "PET_RULE_VIOLATION", "Nascimento no futuro") }
    val rules = store.find("condominium", location())?.data?.get("petRules") as? JsonObject ?: return
    val species = rules.array("allowedSpecies")
    if (species.isNotEmpty() && data["species"] !in species) fail(422, "PET_RULE_VIOLATION", "Espécie não permitida")
    val maximum = rules.text("maxPetsPerNode")?.toIntOrNull()
    if (maximum != null && store.list("pet", locationId, filters = mapOf("nodeId" to nodeId!!)).count { it.id != currentId } >= maximum)
        fail(422, "PET_RULE_VIOLATION", "Quantidade máxima de pets por unidade atingida")
}
internal fun petHandlers(): Map<String, V1Handler> = mapOf(
    "listPets" to V1Handler { c -> c.listResponse("pet", true) { c.pet(it) } },
    "getPet" to V1Handler { c -> V1Response(c.pet(c.record("pet", "petId"))) },
    "createPet" to V1Handler { c ->
        c.validatePet(c.input)
        V1Response(c.pet(c.save("pet", c.input.merge(obj("nodeId" to c.checkedNode(required = true))))), 201)
    },
    "updatePet" to V1Handler { c ->
        val row = c.record("pet", "petId")
        val data = row.data.merge(c.input)
        c.validatePet(data, row.id)
        V1Response(c.pet(c.change(row, data.merge(obj("nodeId" to c.checkedNode(data, true))))))
    },
    "deletePet" to V1Handler { c ->
        val row = c.record("pet", "petId")
        if (c.store.list("pet_alert", c.locationId, filters = mapOf("petId" to row.id, "status" to "open")).isNotEmpty())
            c.fail(409, "PET_HAS_OPEN_ALERT", "Resolva o alerta antes de excluir o pet")
        c.remove(row)
    },
    "adminListPets" to V1Handler { c -> c.listResponse("pet") { row ->
        obj("pet" to c.pet(row), "node" to c.node(row.data.text("nodeId")), "ownerName" to c.personName(row.ownerId))
    } },
    "addVaccination" to V1Handler { c ->
        val pet = c.record("pet", "petId")
        vaccinationDates(c.input)
        c.result("Vaccination", c.save("vaccination", c.input.merge(obj("petId" to pet.id,
            "nextDueAt" to c.input["nextDueAt"], "veterinarian" to c.input["veterinarian"], "attachmentUrl" to null))), 201)
    },
    "deleteVaccination" to V1Handler { c ->
        val pet = c.record("pet", "petId")
        val row = c.record("vaccination", "vaccinationId")
        if (row.data.text("petId") != pet.id) c.fail(404, "NOT_FOUND", "Vacina não encontrada")
        c.remove(row)
    },
    "listPetAlerts" to V1Handler { c -> c.listResponse("pet_alert") { c.petAlert(it) } },
    "adminListPetAlerts" to V1Handler { c -> c.listResponse("pet_alert") { c.petAlert(it) } },
    "getPetAlert" to V1Handler { c -> V1Response(c.petAlert(c.store.get("pet_alert", c.id("alertId"), c.locationId))) },
    "createPetAlert" to V1Handler { c ->
        val petId = c.input.text("petId")
        if (c.input.text("kind") == "lost" && petId == null) c.fail(422, "PET_REQUIRED", "Informe o pet desaparecido")
        petId?.let { c.owned(c.store.get("pet", it, c.locationId)) }
        if (petId != null && c.store.list("pet_alert", c.locationId, filters = mapOf("petId" to petId, "status" to "open")).isNotEmpty())
            c.fail(409, "PET_ALERT_ALREADY_OPEN", "O pet já tem um alerta aberto")
        val row = c.save("pet_alert", c.input.merge(obj("nodeId" to c.unitId, "status" to "open")))
        c.broadcast("pet_alert", row.id, "Alerta de pet", c.input.text("description"))
        V1Response(c.petAlert(row), 201)
    },
    "resolvePetAlert" to V1Handler { c ->
        val row = c.record("pet_alert", "alertId")
        if (row.data.text("status") != "open") c.fail(409, "PET_ALERT_NOT_OPEN", "Alerta não está aberto")
        V1Response(c.petAlert(c.change(row, obj("status" to "resolved", "resolvedAt" to now()))))
