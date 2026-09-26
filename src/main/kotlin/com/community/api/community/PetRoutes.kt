package com.community.api.community

import com.community.api.core.*
import com.community.api.platform.Location
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

internal fun Tx.enforcePetRules(ctx: Context, input: PetInput, existingId: String? = null) {
    val rules = requireRecord("location", ctx.locationId!!, ctx.tenantId).decode<Location>().petRules
    if (rules.vaccinationRequired && input.vaccinationUrls.isEmpty()) badRequest("Vaccination documents are required at this location")
    if (rules.allowedSpecies.isNotEmpty() && rules.allowedSpecies.none { it.equals(input.species, ignoreCase = true) }) badRequest("Species is not permitted by this location")
    rules.maxPetsPerUnit?.let { limit ->
        if (input.unitId == null) badRequest("This location requires a unit for pet registration")
        val count = list("pet", ctx.tenantId, ctx.locationId).count { it.id != existingId && it.decode<PetInput>().unitId == input.unitId }
        if (count >= limit) conflict("The unit has reached the configured pet limit")
    }
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
                tx.enforcePetRules(ctx, input)
                tx.saved(ctx, "pet", body(input))
            })
        }
        put("/{id}") {
            val input = call.receive<PetInput>().validated()
            call.respond(db.query { tx ->
                val ctx = tx.authorizeAny(call.actor(tx), call.locationId(), setOf("pets.manage.own", "pets.manage"), "pets")
                val row = tx.record(ctx, "pet", call.resourceId())
                tx.own(ctx, row, "pets.manage")
                tx.requireUnit(ctx, input.unitId, "pets.manage")
                tx.enforcePetRules(ctx, input, row.id)
                tx.changed(ctx, row, body(input), "pet.updated")
            })
        }
        delete("/{id}") {
            db.query { tx ->
                val ctx = tx.authorizeAny(call.actor(tx), call.locationId(), setOf("pets.manage.own", "pets.manage"), "pets")
                val row = tx.record(ctx, "pet", call.resourceId())
                tx.own(ctx, row, "pets.manage")
                tx.delete(row)
                tx.audit(ctx, "pet.deleted", row.id)
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
    route("/lost-pets") {
        get {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorizeAny(call.actor(tx), call.locationId(), setOf("pets.read.own", "pets.read.all"), "pets")
                tx.list("lost_pet", ctx.tenantId, ctx.locationId).map { LostPetView(it.id, it.decode<LostPet>(), it.createdAt) }
            })
        }
        post {
            val input = call.receive<LostPetInput>()
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "pets.create", "pets")
                val row = tx.record(ctx, "pet", input.petId)
                tx.own(ctx, row, "pets.manage")
                val pet = row.decode<PetInput>()
                val notice = LostPet(pet.name, pet.species, pet.photoUrl, text(input.message, "message", 2000), text(input.lastSeen, "lastSeen", 300))
                val saved = tx.saved(ctx, "lost_pet", body(notice))
                LostPetView(saved.id, notice, saved.createdAt)
            })
        }
        post("/{id}/resolve") {
            call.respond(db.query { tx ->
                val ctx = tx.authorizeAny(call.actor(tx), call.locationId(), setOf("pets.manage.own", "pets.manage"), "pets")
                val row = tx.record(ctx, "lost_pet", call.resourceId())
                tx.own(ctx, row, "pets.manage")
                val saved = tx.changed(ctx, row, body(row.decode<LostPet>().copy(resolved = true)), "lost_pet.resolved")
                LostPetView(saved.id, saved.decode<LostPet>(), saved.createdAt)
            })
        }
    }
}
