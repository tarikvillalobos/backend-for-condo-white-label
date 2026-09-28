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
    if (operation.id in setOf("healthLive","healthReady")) {
        val healthy = operation.id == "healthLive" || db.healthy()
        respondText(obj("status" to if (healthy) "ok" else "down").toString(), ContentType.Application.Json,
            if (healthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable)
        return
    }
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
            if (!request.contentType().match(ContentType.Application.Json)) throw ApiException(415,"VALIDATION_ERROR","Use application/json")
            val text = receiveText()
            if (text.isBlank()) obj() else json.parseToJsonElement(text) as? JsonObject ?: throw ApiException(422,"VALIDATION_ERROR","JSON object required")
        } else obj()
        validateRequest(operation,path,query,headers,input)
        brandId = headers.entries.firstOrNull { it.key.equals("X-Brand-Id",true) }?.value
        if (brandId.isNullOrBlank()) throw ApiException(400,"VALIDATION_ERROR","X-Brand-Id is required")
        run {
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
        if (failure is kotlinx.serialization.SerializationException || failure is IllegalArgumentException)
            problem(400,"VALIDATION_ERROR","Malformed request",requestId)
        else {
            application.log.error("V1 operation {} failed ({}) requestId={}",operation.id,failure.javaClass.simpleName,requestId)
            if (failure is IllegalStateException) application.log.error("Contract/server invariant: {}", failure.message)
            problem(500,"INTERNAL_ERROR","Unexpected server error",requestId)
        }
    }
    try {
        recordRequest(db,requestId,operation,tenantId,brandId,context?.locationId,context?.principal,request.httpMethod.value,operation.path,
            result.status,(System.nanoTime()-started)/1_000_000,(result.body as? JsonObject)?.string("code"),replay)
    } catch (failure: Exception) {
        application.log.error("Request audit persistence failed requestId={} type={}",requestId,failure.javaClass.simpleName)
        val unavailable = problem(503,"SERVICE_UNAVAILABLE","Audit storage unavailable; retry with the same idempotency key",requestId)
        respondText(unavailable.body.toString(),ContentType.parse("application/problem+json"),HttpStatusCode.ServiceUnavailable)
        return
    }
    result.headers.filterKeys { !it.equals("Content-Type",true) }.forEach { (name,value) -> response.headers.append(name,value) }
    if (result.body == JsonNull && result.status < 400) respond(HttpStatusCode.fromValue(result.status))
    else respondText(result.body.toString(),ContentType.parse(if (result.status >= 400) "application/problem+json" else "application/json"),HttpStatusCode.fromValue(result.status))
}

fun problem(status: Int, code: String, detail: String, requestId: String): V1Response = V1Response(obj(
    "type" to "about:blank","title" to HttpStatusCode.fromValue(status).description,"status" to status,"code" to code,"detail" to detail,"requestId" to requestId),status)

private fun validateRequest(op: ContractOperation, path: Map<String,String>, query: Map<String,String>, headers: Map<String,String>, input: JsonObject) {
    op.definition["parameters"]!!.jsonArray.forEach { reference ->
        val param = Contract.resolve(reference.jsonObject)
        val name = param.string("name")!!
        val raw = when (param.string("in")) {
            "path" -> path[name]
            "header" -> headers.entries.firstOrNull { it.key.equals(name,true) }?.value
            else -> query[name]
        }
        if (raw == null && param["required"] == JsonPrimitive(true)) throw ApiException(400,"VALIDATION_ERROR","$name is required")
        if (raw != null) {
            val schema = Contract.resolve(param["schema"]!!.jsonObject)
            val parsed = when(schema.string("type")) {
                "integer" -> raw.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(raw)
                "boolean" -> raw.toBooleanStrictOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(raw)
                else -> JsonPrimitive(raw)
            }
            Contract.validate(schema,parsed,name)
        }
    }
    op.definition["requestBody"]?.jsonObject?.get("content")?.jsonObject?.get("application/json")?.jsonObject?.get("schema")?.jsonObject?.let { Contract.validate(it,input) }
}

private fun validateResponse(op: ContractOperation, response: V1Response) {
    if (response.status >= 400 || response.status == 204) return
    val definition = op.definition["responses"]!!.jsonObject[response.status.toString()]?.jsonObject ?: error("Undocumented success status ${op.id} ${response.status}")
    val schema = definition["content"]?.jsonObject?.get("application/json")?.jsonObject?.get("schema")?.jsonObject ?: return
    val errors = Contract.errors(schema,response.body,"response")
    check(errors.isEmpty()) { "Response contract violation ${op.id}: ${errors.take(6)}" }
}

private fun scopeFor(tx: Tx, op: ContractOperation, tenant: String, brand: String, path: Map<String,String>, input: JsonObject): String? {
    if (op.method == "get") return null
    val store = V1Store(tx,tenant,brand)
    val location = path["condominiumId"] ?: path["membershipId"]?.let { store.find("membership",it)?.locationId }
        ?: path["lockerId"]?.let { store.find("locker",it)?.locationId } ?: path["parcelId"]?.let { store.find("parcel",it)?.locationId }
        ?: input.string("condominiumId") ?: input.string("nodeId")?.let { store.find("node",it)?.locationId }
    val identity = if (op.path.startsWith("/auth/")) Secrets.hash(input.string("identifier") ?: input.string("refreshToken") ?: path["challengeId"] ?: "public") else null
    return "v1:$tenant:$brand:${location ?: identity ?: "platform"}"
}

private fun healthHandlers(): Map<String,V1Handler> = mapOf(
    "healthLive" to V1Handler { V1Response(obj("status" to "UP")) },
    "healthReady" to V1Handler { V1Response(obj("status" to "UP")) },
)

private val documentationHtml = """<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><title>Community API</title>
<meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5.30.2/swagger-ui.css"></head>
<body><div id="swagger-ui"></div><script src="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5.30.2/swagger-ui-bundle.js"></script>
<script>SwaggerUIBundle({url:'/v1/openapi.json',dom_id:'#swagger-ui',persistAuthorization:false,deepLinking:true});</script></body></html>"""
