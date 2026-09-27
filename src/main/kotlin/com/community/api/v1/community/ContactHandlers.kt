package com.community.api.v1.community

import com.community.api.v1.*
import kotlinx.serialization.json.*

private val contactDefaults = obj("phone" to null, "whatsapp" to null, "email" to null, "website" to null,
    "operatingHours" to null, "notes" to null, "sortOrder" to 0, "active" to true)
internal fun contactHandlers(): Map<String, V1Handler> = mapOf(
    "listContacts" to V1Handler { c -> c.contactList(true) },
    "adminListContacts" to V1Handler { c -> c.contactList(false) },
    "adminCreateContact" to V1Handler { c -> c.result("Contact", c.save("contact", contactDefaults.merge(c.input)), 201) },
    "adminUpdateContact" to V1Handler { c -> c.result("Contact", c.change(c.store.get("contact", c.id("contactId"), c.locationId), c.input)) },
    "adminDeleteContact" to V1Handler { c -> c.remove(c.store.get("contact", c.id("contactId"), c.locationId)) },
)
private fun V1Context.contactList(active: Boolean) = V1Response(obj("items" to JsonArray(
    store.list("contact", locationId).filter { !active || it.data.flag("active", true) }.sortedWith(compareBy({ it.data.number("sortOrder") }, { it.data.text("name") }))
        .map { view("Contact", it) },
)))
