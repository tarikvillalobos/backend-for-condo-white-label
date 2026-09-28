package com.community.api.v1.deliveries

import com.community.api.v1.*
import kotlinx.serialization.json.*

internal fun recipientHandlers(): Map<String,V1Handler> = mapOf(
    "listRecipients" to V1Handler { c ->
        val member = c.membership ?: c.fail(404,"RESOURCE_NOT_FOUND","Membership not found")
        val account = c.tx.get("account",c.userId,c.tenantId)
        val own = obj("id" to member.id,"name" to (member.data.text("name") ?: account?.data?.text("name") ?: "Morador"),
            "relationship" to (member.data.text("role") ?: "resident"),"kind" to "person")
        val node = member.data.text("nodeId")?.let { c.store.get("node",it,member.locationId) }
        val shared = node?.takeIf { it.data["receivesAsEntity"] == JsonPrimitive(true) }?.let {
            obj("id" to it.id,"name" to it.data.text("label"),"relationship" to "node","kind" to "node")
        }
        V1Response(obj("items" to listOfNotNull(own,shared)))
    },
)
