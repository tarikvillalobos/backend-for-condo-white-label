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
    if (vaccinationUrls.size > 10) badRequest("At most 10 vaccination attachments are allowed")
    return copy(name = text(name, "name", 100), species = text(species, "species", 60),
        identification = identification?.let { text(it, "identification", 120) },
        photoUrl = photoUrl?.let(::url), vaccinationUrls = vaccinationUrls.map(::url))
}
internal fun Route.petRoutes(db: Database) {
    route("/pets") {
        get {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorizeAny(call.actor(tx), call.locationId(), setOf("pets.read.own", "pets.read.all"), "pets")
                tx.visible(ctx, "pet", "pets.read.all")
            })
        }
        post {
            val input = call.receive<PetInput>().validated()
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "pets.create", "pets")
                tx.requireUnit(ctx, input.unitId, "pets.manage")
                tx.saved(ctx, "pet", body(input))
            })
