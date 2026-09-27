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

private suspend fun ApplicationCall.executeV1(db: Database, operation: ContractOperation, handler: V1Handler) {
    val requestId = callId ?: UUID.randomUUID().toString()
    val started = System.nanoTime()
    var tenantId: String? = null
    var brandId: String? = null
    var context: V1Context? = null
    var replay = false
    val result = try {
        val path = parameters.names().associateWith { parameters[it]!! }
        val query = request.queryParameters.names().associateWith { request.queryParameters[it]!! }
        val headers = request.headers.names().associateWith { request.headers[it]!! } + ("X-Remote-Host" to request.local.remoteHost)
        val input = if (operation.definition.containsKey("requestBody")) {
            val text = receiveText()
            if (text.isBlank()) obj() else json.parseToJsonElement(text) as? JsonObject ?: throw ApiException(422,"VALIDATION_ERROR","JSON object required")
        } else obj()
        validateRequest(operation,path,query,headers,input)
        val health = operation.id in setOf("healthLive","healthReady")
        brandId = headers.entries.firstOrNull { it.key.equals("X-Brand-Id",true) }?.value
        if (!health && brandId.isNullOrBlank()) throw ApiException(400,"VALIDATION_ERROR","X-Brand-Id is required")
        if (health) handler.handle(V1Context(db.scopedTx(null) { it },operation.id,"","",requestId))
        else {
            tenantId = db.scopedQuery(null) { it.tenantForBrand(brandId!!) }
            val scope = db.scopedQuery(null) { tx -> scopeFor(tx,operation,tenantId!!,brandId!!,path,input) }
            db.scopedQuery(scope) { tx ->
                val c = authorizeV1(tx,operation,brandId!!,tenantId!!,headers,path,input,query,requestId)
                context = c
                tx.requestMetadata(requestId,operation.id,c.principal)
                val (response,wasReplay) = c.idempotent(operation,handler)
                replay = wasReplay
                if (!wasReplay && operation.definition["x-audit"]?.jsonObject?.get("layers")?.jsonArray?.contains(JsonPrimitive("event")) == true) {
                    appendAudit(c,operation.id,outcome=if (response.status < 400) "success" else "failed")
                }
                validateResponse(operation,response)
                response
            }
        }
    } catch (failure: ApiException) {
        problem(failure.status,failure.code.uppercase(),failure.message,requestId)
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
