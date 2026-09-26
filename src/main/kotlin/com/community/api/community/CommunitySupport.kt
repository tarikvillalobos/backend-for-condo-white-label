package com.community.api.community

import com.community.api.core.*
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.*
import java.net.URI
import java.time.Instant

internal fun ApplicationCall.locationId() = parameters["locationId"] ?: badRequest("Location is required")
internal fun ApplicationCall.resourceId(name: String = "id") = parameters[name] ?: badRequest("Resource is required")
internal fun text(value: String, field: String, max: Int = 500): String = value.trim().also {
    if (it.isEmpty() || it.length > max || '\u0000' in it) badRequest("$field must contain 1 to $max characters")
}
internal fun instant(value: String, field: String): Instant = try { Instant.parse(value) } catch (_: Exception) {
    badRequest("$field must be an ISO-8601 instant")
}
internal fun url(value: String): String = value.also {
    val parsed = try { URI(it) } catch (_: Exception) { badRequest("Invalid attachment URL") }
    if (it.length > 2048 || parsed.scheme != "https" || parsed.host.isNullOrEmpty() || parsed.userInfo != null)
        badRequest("Attachments require a public HTTPS URL without credentials")
}
internal fun Tx.requireUnit(ctx: Context, unitId: String?, managePermission: String) {
    if (unitId == null) return
    requireRecord("unit", unitId, ctx.tenantId, ctx.locationId)
    if (ctx.can(managePermission)) return
    val member = list("membership", ctx.tenantId).any {
        it.data["userId"]?.jsonPrimitive?.content == ctx.userId &&
            it.data["locationId"]?.jsonPrimitive?.content == ctx.locationId &&
            it.data["unitId"]?.jsonPrimitive?.content == unitId &&
            it.data["active"]?.jsonPrimitive?.booleanOrNull == true &&
            it.data["expiresAt"]?.jsonPrimitive?.contentOrNull.let { expiry -> expiry == null || instant(expiry, "expiresAt").isAfter(Instant.now()) }
    }
    if (!member) forbidden()
}
internal fun Tx.own(ctx: Context, record: Record, permission: String) {
    if (record.ownerId != ctx.userId && !ctx.can(permission)) forbidden()
}
internal fun Tx.visible(ctx: Context, kind: String, allPermission: String): List<Record> =
    list(kind, ctx.tenantId, ctx.locationId, if (ctx.can(allPermission)) null else ctx.userId)
internal fun Tx.saved(ctx: Context, kind: String, data: JsonObject, ownerId: String? = ctx.userId): Record =
