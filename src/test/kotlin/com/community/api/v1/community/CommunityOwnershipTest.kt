package com.community.api.v1.community

import com.community.api.core.ApiException
import com.community.api.v1.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CommunityOwnershipTest {
    @Test fun `vehicles enforce membership ownership duplicate plates and version`() = CommunityFixture().use { f ->
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
