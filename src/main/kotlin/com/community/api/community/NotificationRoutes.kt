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
