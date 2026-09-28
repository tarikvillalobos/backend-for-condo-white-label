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
    if (delivery) "DeliveryNotice" else "InboxNotification", row, obj("parcelId" to row.data.text("referenceId"), "readAt" to row.data["readAt"]),
)
internal fun notificationHandlers(): Map<String, V1Handler> = mapOf(
    "listInbox" to V1Handler { c ->
        val response = c.listResponse("notification", true) { row ->
        if (c.query["unreadOnly"] == "true" && row.data.text("readAt") != null) JsonNull else c.notification(row)
        }
        V1Response(response.body.jsonObject.merge(obj("unreadCount" to c.unreadNotifications(false))))
    },
    "markInboxRead" to V1Handler { c ->
        val row = c.record("notification", "notificationId")
        if (row.data.text("readAt") == null) c.change(row, obj("readAt" to now()), "notification.read")
        V1Response(status = 204)
    },
    "markInboxAllRead" to V1Handler { c ->
        c.store.list("notification", c.locationId, c.userId, mapOf("membershipId" to c.membershipId!!)).forEach {
            if (it.data.text("readAt") == null) c.change(it, obj("readAt" to now()), "notification.read")
        }
        V1Response(status = 204)
    },
    "listDeliveryNotices" to V1Handler { c ->
        val page = c.listResponse("notification", true, mapOf("kind" to "parcel")) { c.notification(it, true) }.body.jsonObject
        V1Response(obj("items" to page["items"], "pageInfo" to page["page"], "unreadCount" to c.unreadNotifications(true)))
    },
    "markNoticeRead" to V1Handler { c ->
        val row = c.record("notification", "noticeId")
        if (row.data.text("kind") != "parcel") c.fail(404, "NOT_FOUND", "Aviso não encontrado")
        V1Response(c.notification(if (row.data.text("readAt") == null) c.change(row, obj("readAt" to now()), "notification.read") else row, true))
    },
    "getNotificationDelivery" to V1Handler { c ->
        val row = c.record("notification", "notificationId")
        val deliveries = c.store.list("notification_delivery", c.locationId, filters = mapOf("notificationId" to row.id))
        V1Response(obj("notificationId" to row.id, "channels" to JsonArray(deliveries.map { it.data })))
    },
)
