package com.community.api.community

import com.community.api.core.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class InboxNotification(val title: String, val message: String, val readAt: String? = null)
@Serializable
data class NotificationPreferences(val push: Boolean = true, val email: Boolean = true, val sms: Boolean = false, val language: String = "pt-BR")
@Serializable
data class UnreadNotifications(val unread: Int)

internal fun Tx.notificationContext(actor: Actor, locationId: String?, permission: String): Context {
    if (locationId != null) return authorize(actor, locationId, permission, "notifications")
    for (membership in activeMemberships(actor.tenantId, actor.userId)) {
        try { return authorize(actor, membership.locationId, permission, "notifications") }
        catch (error: ApiException) { if (error.status !in setOf(403, 404)) throw error }
    }
    forbidden()
}
internal fun Tx.inbox(actor: Actor): List<Record> = list("notification", actor.tenantId, ownerId = actor.userId).filter {
    try { notificationContext(actor, it.locationId, "notifications.read"); true }
    catch (error: ApiException) { if (error.status !in setOf(403, 404)) throw error; false }
}
internal fun Route.notificationRoutes(db: Database) {
    route("/api/v1/notifications") {
        get {
            call.respondPage(db.query { tx -> tx.inbox(call.actor(tx)).sortedByDescending { it.createdAt } })
        }
        get("/unread-count") {
            call.respond(db.query { tx -> UnreadNotifications(tx.inbox(call.actor(tx)).count { it.decode<InboxNotification>().readAt == null }) })
        }
        post("/{id}/read") {
            call.respond(db.query { tx ->
                val actor = call.actor(tx)
                val row = tx.requireRecord("notification", call.resourceId(), actor.tenantId)
