package com.community.api.community

import com.community.api.core.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class AnnouncementInput(val title: String, val message: String, val unitId: String? = null,
    val publishAt: String? = null, val expiresAt: String? = null, val pinned: Boolean = false,
    val priority: String = "normal", val attachments: List<String> = emptyList(), val acknowledgmentRequired: Boolean = false)
@Serializable
data class Announcement(val content: AnnouncementInput, val archived: Boolean = false)
@Serializable
data class Acknowledgment(val resourceId: String, val acknowledgedAt: String = Instant.now().toString())

internal fun AnnouncementInput.validated(): AnnouncementInput {
    val published = publishAt?.let { instant(it, "publishAt") } ?: Instant.now()
    if (expiresAt != null && !instant(expiresAt, "expiresAt").isAfter(published)) badRequest("Expiration must follow publication")
    if (priority !in setOf("normal", "high", "urgent")) badRequest("Invalid priority")
    if (attachments.size > 10) badRequest("At most 10 attachments are allowed")
    return copy(title = text(title, "title", 160), message = text(message, "message", 10000), attachments = attachments.map(::url))
}
internal fun Announcement.visibleAt(now: Instant = Instant.now()): Boolean = !archived &&
    (content.publishAt == null || !instant(content.publishAt, "publishAt").isAfter(now)) &&
    (content.expiresAt == null || instant(content.expiresAt, "expiresAt").isAfter(now))

internal fun Route.announcementRoutes(db: Database) {
    route("/announcements") {
        get {
            val rows = db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "announcements.read", "announcements")
                tx.list("announcement", ctx.tenantId, ctx.locationId).filter {
                    val item = it.decode<Announcement>()
                    (ctx.can("announcements.manage") || item.visibleAt()) && tx.audience(ctx, item.content.unitId)
                }.sortedByDescending { it.decode<Announcement>().content.pinned }
            }
            call.respondPage(rows)
        }
        post {
            val input = call.receive<AnnouncementInput>().validated()
            val row = db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "announcements.manage", "announcements")
                tx.requireUnit(ctx, input.unitId, "announcements.manage")
                tx.saved(ctx, "announcement", body(Announcement(input)))
            }
            call.respond(HttpStatusCode.Created, row)
        }
        put("/{id}") {
            val input = call.receive<AnnouncementInput>().validated()
            call.respond(db.query { tx ->
                val ctx = tx.authorize(call.actor(tx), call.locationId(), "announcements.manage", "announcements")
                tx.requireUnit(ctx, input.unitId, "announcements.manage")
                tx.changed(ctx, tx.record(ctx, "announcement", call.resourceId()), body(Announcement(input)), "announcement.updated")
            })
        }
        post("/{id}/archive") {
