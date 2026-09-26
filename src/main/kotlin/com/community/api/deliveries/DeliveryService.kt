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
