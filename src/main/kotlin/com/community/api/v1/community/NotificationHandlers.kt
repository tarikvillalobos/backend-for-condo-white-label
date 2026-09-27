package com.community.api.v1.community

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*

internal fun V1Context.members(): List<Record> = store.list("membership", locationId).filter {
    it.data.text("status") in setOf("active", null) && it.data.flag("active", true)
}
internal fun V1Context.notifyMember(member: Record, kind: String, referenceId: String, title: String, message: String?) {
    val owner = member.data.text("userId") ?: member.ownerId ?: return
    val row = store.create("notification", obj("membershipId" to member.id, "kind" to kind,
        "referenceId" to referenceId, "title" to title, "body" to message, "readAt" to null), member.locationId, owner)
    audit("notification.created", row)
}
internal fun V1Context.broadcast(kind: String, referenceId: String, title: String, message: String?, targets: JsonArray = JsonArray(emptyList())) {
    members().filter { member -> targets.isEmpty() || targets.any { inSubtree(member.data.text("nodeId"), it.jsonPrimitive.content) } }
        .forEach { notifyMember(it, kind, referenceId, title, message) }
}
private fun V1Context.notification(row: Record, delivery: Boolean = false) = view(
