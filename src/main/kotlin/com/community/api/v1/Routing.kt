package com.community.api.v1

import com.community.api.core.*
import com.community.api.v1.community.communityHandlers
import com.community.api.v1.deliveries.deliveryHandlers
import com.community.api.v1.identity.identityHandlers
import com.community.api.v1.platform.platformHandlers
import com.community.api.v1.reservations.reservationHandlers
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.util.UUID

fun v1Handlers(): Map<String,V1Handler> {
    val groups = listOf(identityHandlers(),communityHandlers(),deliveryHandlers(),reservationHandlers(),platformHandlers(),contextHandlers(),fileHandlers(),auditHandlers(),healthHandlers())
    val entries = groups.flatMap { it.entries }
    check(entries.map { it.key }.distinct().size == entries.size) { "Duplicate operation handlers" }
    return entries.associate { it.key to it.value }
}

fun Route.v1Routes(db: Database) {
    val handlers = v1Handlers()
    val missing = Contract.operations.map { it.id }.toSet() - handlers.keys
    check(missing.isEmpty()) { "Unimplemented contract operations: $missing" }
    Contract.operations.forEach { operation ->
        route("/v1${operation.path}",HttpMethod.parse(operation.method.uppercase())) {
            handle { call.executeV1(db,operation,handlers.getValue(operation.id)) }
        }
    }
    get("/v1/openapi.yaml") { call.respondText(Contract.yaml,ContentType.parse("application/yaml")) }
    get("/v1/openapi.json") { call.respond(Contract.document) }
    get("/docs") { call.respondText(documentationHtml,ContentType.Text.Html) }
    fileRoutes(db)
}

