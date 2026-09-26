package com.community.api.community

import com.community.api.core.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class ContactInput(val name: String, val category: String, val phone: String? = null,
    val email: String? = null, val website: String? = null, val operatingHours: String? = null)

private fun ContactInput.validated(): ContactInput {
    if (category !in setOf("emergency", "administration", "maintenance", "service")) badRequest("Invalid contact category")
    if (phone == null && email == null && website == null) badRequest("At least one contact method is required")
    if (phone != null && !phone.matches(Regex("[+0-9() .-]{3,40}"))) badRequest("Invalid phone number")
    if (email != null && (email.length > 254 || !email.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")))) badRequest("Invalid email")
    return copy(name = text(name, "name", 160), website = website?.let(::url), operatingHours = operatingHours?.let { text(it, "operatingHours", 500) })
}
internal fun Route.contactRoutes(db: Database) {
    route("/contacts") {
        get {
            call.respondPage(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "contacts.read", "contacts")
                tx.list("contact", ctx.tenantId, ctx.locationId)
            })
        }
        post {
            val input = call.receive<ContactInput>().validated()
            call.respond(HttpStatusCode.Created, db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "contacts.manage", "contacts")
                tx.saved(ctx, "contact", body(input))
            })
        }
        put("/{id}") {
            val input = call.receive<ContactInput>().validated()
            call.respond(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "contacts.manage", "contacts")
                tx.changed(ctx, tx.record(ctx, "contact", call.resourceId()), body(input), "contact.updated")
