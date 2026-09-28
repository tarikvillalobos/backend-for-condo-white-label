package com.community.api.v1.community

import com.community.api.core.ApiException
import com.community.api.v1.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CommunityOwnershipTest {
    @Test fun `vehicles enforce membership ownership duplicate plates and version`(): Unit = CommunityFixture().use { f ->
        val vehicle = f.run("createVehicle", obj("plate" to "ABC1D23", "model" to "Carro", "kind" to "car"))
        assertEquals(1, f.run("listVehicles").items().size)
        assertEquals(0, f.run("listVehicles", other = true).items().size)
        assertEquals(404, assertFailsWith<ApiException> { f.run("updateVehicle", obj("model" to "Outro"), mapOf("vehicleId" to vehicle.id()), other = true, headers = mapOf("If-Match" to "\"1\"")) }.status)
        assertEquals(409, assertFailsWith<ApiException> { f.run("createVehicle", obj("plate" to "ABC1D23", "model" to "Outro", "kind" to "car")) }.status)
        assertEquals(428, assertFailsWith<ApiException> { f.run("updateVehicle", obj("model" to "Novo"), mapOf("vehicleId" to vehicle.id())) }.status)
        f.run("updateVehicle", obj("model" to "Novo"), mapOf("vehicleId" to vehicle.id()), headers = mapOf("If-Match" to "\"1\""))
        assertEquals(412, assertFailsWith<ApiException> { f.run("updateVehicle", obj("model" to "Velho"), mapOf("vehicleId" to vehicle.id()), headers = mapOf("If-Match" to "\"1\"")) }.status)
        f.run("deleteVehicle", ids = mapOf("vehicleId" to vehicle.id()))
        assertTrue(f.run("listVehicles").items().isEmpty())
    }
    @Test fun `node assignment cannot escape membership subtree`() = CommunityFixture().use { f ->
        assertEquals(403, assertFailsWith<ApiException> {
            f.run("createVehicle", obj("nodeId" to f.otherUnit, "plate" to "ABC1D23", "model" to "Carro", "kind" to "car"))
        }.status)
    }
    @Test fun `pet lost alerts require ownership and prevent duplicate open alerts`() = CommunityFixture().use { f ->
        val pet = f.run("createPet", obj("name" to "Nina", "species" to "dog", "sex" to "female"))
        val alert = obj("kind" to "lost", "petId" to pet.id(), "description" to "Desapareceu", "species" to "dog")
        assertEquals(404, assertFailsWith<ApiException> { f.run("createPetAlert", alert, other = true) }.status)
        val created = f.run("createPetAlert", alert)
        assertEquals(1, f.run("listPetAlerts", other = true).items().size)
        assertEquals(409, assertFailsWith<ApiException> { f.run("createPetAlert", alert) }.status)
        assertEquals(409, assertFailsWith<ApiException> { f.run("deletePet", ids = mapOf("petId" to pet.id())) }.status)
        assertEquals(404, assertFailsWith<ApiException> { f.run("resolvePetAlert", ids = mapOf("alertId" to created.id()), other = true) }.status)
        f.run("resolvePetAlert", ids = mapOf("alertId" to created.id()))
        f.run("deletePet", ids = mapOf("petId" to pet.id()))
    }
    @Test fun `vaccine dates must be valid and chronological`() = CommunityFixture().use { f ->
        val pet = f.run("createPet", obj("name" to "Nina", "species" to "cat", "sex" to "female"))
        assertEquals(422, assertFailsWith<ApiException> { f.run("addVaccination", obj("vaccine" to "Raiva", "appliedAt" to "2099-01-01"), mapOf("petId" to pet.id())) }.status)
        val vaccine = f.run("addVaccination", obj("vaccine" to "Raiva", "appliedAt" to "2025-01-01", "nextDueAt" to "2026-01-01"), mapOf("petId" to pet.id()))
        assertEquals(1, f.run("getPet", ids = mapOf("petId" to pet.id())).body.jsonObject["vaccinations"]!!.jsonArray.size)
        f.run("deleteVaccination", ids = mapOf("petId" to pet.id(), "vaccinationId" to vaccine.id()))
    }
}
