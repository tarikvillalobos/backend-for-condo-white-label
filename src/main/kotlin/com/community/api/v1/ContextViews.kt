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
