package com.community.api.community

import com.community.api.core.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class PetInput(val name: String, val species: String, val unitId: String? = null,
    val identification: String? = null, val photoUrl: String? = null, val vaccinationUrls: List<String> = emptyList())
@Serializable
data class LostPetInput(val petId: String, val message: String, val lastSeen: String)
@Serializable
data class LostPet(val petName: String, val species: String, val photoUrl: String?, val message: String, val lastSeen: String, val resolved: Boolean = false)
@Serializable
data class LostPetView(val id: String, val details: LostPet, val createdAt: String)

internal fun PetInput.validated(): PetInput {
