package com.community.api.identity

import com.community.api.core.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*

internal fun Route.sessionRoutes(db: Database) {
    route("/api/v1/me/sessions") {
        get {
            val token = call.identityBearer()
            call.respond(db.query { tx ->
                val actor = tx.authenticate(token)
                tx.list("session", actor.tenantId, ownerId = actor.userId).mapNotNull { record ->
                    val session = record.decode<SessionData>()
                    if (session.revoked || expired(session.expiresAt)) null
                    else SessionView(record.id, session.device, record.createdAt, session.expiresAt, record.id == actor.sessionId)
                }
            })
        }
        delete {
            val token = call.identityBearer()
            db.query { tx ->
                val actor = tx.authenticate(token)
                tx.revokeSessions(actor.tenantId, actor.userId)
                tx.identityAudit(actor.tenantId, actor.userId, "session.all_revoked")
            }
            call.respond(Accepted())
        }
        delete("/{id}") {
            val token = call.identityBearer()
            val id = call.parameters["id"] ?: badRequest("Session ID required")
            db.query { tx ->
                val actor = tx.authenticate(token)
                val session = tx.get("session", id, actor.tenantId)?.takeIf { it.ownerId == actor.userId } ?: notFound()
                tx.update(session, body(session.decode<SessionData>().copy(revoked = true)))
                tx.identityAudit(actor.tenantId, actor.userId, "session.revoked")
            }
            call.respond(Accepted())
        }
    }
