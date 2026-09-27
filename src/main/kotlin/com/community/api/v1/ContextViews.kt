package com.community.api.v1

import com.community.api.core.Record
import kotlinx.serialization.json.*

fun nodeRef(c: V1Context, id: String): JsonObject {
    val node = c.store.get("node",id,c.locationId)
    return obj("id" to node.id,"type" to (node.data.string("type") ?: node.data.string("typeCode")),"label" to node.data.string("label"))
}

fun modulesView(c: V1Context, locationId: String? = c.locationId, membership: Record? = c.membership): JsonObject {
    val brand = c.store.get("brand",c.brandId).data["modules"]?.jsonObject.orEmpty()
    val location = locationId?.let { c.store.get("condominium",it).data["modules"]?.jsonObject }.orEmpty()
    val member = membership?.data?.get("modules")?.jsonObject.orEmpty()
    return JsonObject(Contract.schemas["Modules"]!!.jsonObject["properties"]!!.jsonObject.keys.associateWith { key ->
        JsonPrimitive(brand[key] != JsonPrimitive(false) && location[key] != JsonPrimitive(false) && member[key] != JsonPrimitive(false))
    })
}

fun capabilitiesView(): JsonObject {
    fun channel(available: Boolean) = obj("available" to available,"reason" to if (available) null else "PROVIDER_NOT_CONFIGURED")
    return obj("features" to obj("manualPickup" to true,"undoManualPickup" to true,"contactEditing" to true,
        "recipients" to true,"supportIssues" to true,"pushRegistration" to true),
        "channels" to obj("inApp" to channel(true),"email" to channel(System.getenv("SMTP_HOST") != null),
            "sms" to channel(false),"whatsapp" to channel(false),"push" to channel(System.getenv("FCM_PROJECT_ID") != null)))
}

fun membershipView(c: V1Context, member: Record): JsonObject {
    val location = member.locationId?.let { c.store.get("condominium",it) }
    val nodeId = member.data.string("nodeId")
    val ancestors = mutableListOf<JsonObject>()
    val seen = mutableSetOf<String>()
    var current = nodeId
    while (current != null && seen.add(current)) {
        val node = c.store.get("node",current,member.locationId)
        ancestors.add(0,nodeRef(c,current)); current = node.data.string("parentId")
    }
    val permissions = Contract.document["x-permission-catalog"]!!.jsonArray.filter { it.jsonObject.string("audience") == "resident" }
        .map { it.jsonObject.string("code")!! }.filter { it != "unit.manage" || member.data.string("role") in setOf("owner","tenant") }
    return obj("id" to member.id,"locationId" to (location?.id ?: member.data.string("lockerId")),
