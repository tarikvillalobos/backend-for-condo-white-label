package com.community.api.deliveries

import com.community.api.core.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.jsonPrimitive

class DeliveryService(private val clock: Clock = Clock.systemUTC()) {
    private fun Context.location(): String = locationId ?: forbidden()
    private fun Context.allow(permission: String) {
        if (!can(permission)) forbidden()
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun event(ctx: Context, action: String) = DeliveryEvent(action, ctx.userId, clock.instant().toString())

    private fun view(record: Record): PackageView {
        val data = record.decode<PackageData>()
        val receipt = data.receipt
        return PackageView(record.id, receipt.recipientId, receipt.description, receipt.carrier,
            receipt.trackingNumber, receipt.lockerId, receipt.compartmentId, receipt.collectionDeadline,
            data.status, data.delegates, data.collectorId, data.history)
    }

    private fun packageRecord(tx: Tx, ctx: Context, id: String): Record =
        tx.requireRecord("package", id, ctx.tenantId, ctx.location())

    private fun canRead(ctx: Context, data: PackageData): Boolean = ctx.can("packages.read.all") ||
        (ctx.can("packages.read.own") && (data.receipt.recipientId == ctx.userId || ctx.userId in data.delegates))

    private fun requireRecipient(ctx: Context, data: PackageData) {
        ctx.allow("packages.read.own")
        if (data.receipt.recipientId != ctx.userId) forbidden()
    }

    private fun requireOutstanding(data: PackageData) {
        if (data.status !in setOf("RECEIVED", "PICKUP_REPORTED")) conflict("Package is no longer available for collection")
    }

    fun list(tx: Tx, ctx: Context): List<PackageView> =
        tx.list("package", ctx.tenantId, ctx.location()).filter { canRead(ctx, it.decode()) }.map(::view)

    fun get(tx: Tx, ctx: Context, id: String): PackageView {
        val record = packageRecord(tx, ctx, id)
        if (!canRead(ctx, record.decode())) forbidden()
        return view(record)
    }

    fun receive(tx: Tx, ctx: Context, request: ReceivePackage, key: String): PackageView {
        ctx.allow("packages.receive")
        if (key.isBlank() || key.length > 128) badRequest("Idempotency-Key must contain 1 to 128 characters")
        if (request.description.isBlank() || request.description.length > 1000) badRequest("Description must contain 1 to 1000 characters")
        if (request.carrier.length > 200 || request.trackingNumber.length > 200) badRequest("Carrier or tracking number is too long")
        if ((request.lockerId == null) != (request.compartmentId == null)) badRequest("Locker and compartment must be supplied together")
        request.collectionDeadline?.let { parseInstant(it) }
        val fingerprint = digest(body(request).toString())
        val keyId = digest("${ctx.tenantId}:${ctx.location()}:$key")
        tx.get("package_receipt_key", keyId, ctx.tenantId)?.let {
            val previous = it.decode<ReceiptKey>()
            if (previous.fingerprint != fingerprint) conflict("Idempotency-Key was already used for a different receipt")
            return view(packageRecord(tx, ctx, previous.packageId))
        }
        tx.requireMember(ctx.tenantId, ctx.location(), request.recipientId)
        val id = UUID.randomUUID().toString()
        request.lockerId?.let { lockerId ->
            val locker = tx.requireRecord("locker", lockerId, ctx.tenantId, ctx.location())
            val data = locker.decode<LockerData>()
            val compartment = data.compartments.find { it.id == request.compartmentId } ?: notFound()
            if (data.maintenance || compartment.maintenance || compartment.packageId != null) conflict("Locker compartment is unavailable")
            tx.update(locker, body(data.copy(compartments = data.compartments.map {
                if (it.id == compartment.id) it.copy(packageId = id) else it
            })))
        }
        val data = PackageData(request, history = listOf(event(ctx, "received")))
