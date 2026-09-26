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
        val record = tx.create("package", ctx.tenantId, ctx.location(), request.recipientId, body(data), id)
        tx.create("package_receipt_key", ctx.tenantId, ctx.location(), ctx.userId, body(ReceiptKey(fingerprint, id)), keyId)
        tx.audit(ctx, "package.received", id)
        tx.notify(ctx.tenantId, ctx.location(), request.recipientId, "Package received", "A delivery is ready for collection.")
        return view(record)
    }

    fun credential(tx: Tx, ctx: Context, id: String, minutes: Int): PickupCredential {
        if (minutes !in 1..1440) badRequest("Credential validity must be between 1 and 1440 minutes")
        val record = packageRecord(tx, ctx, id)
        val data = record.decode<PackageData>()
        requireRecipient(ctx, data)
        requireOutstanding(data)
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val expiresAt = clock.instant().plusSeconds(minutes * 60L).toString()
        tx.update(record, body(data.copy(credentialHash = digest(secret), credentialExpiresAt = expiresAt,
            history = data.history + event(ctx, "credential_issued"))))
        tx.audit(ctx, "package.credential_issued", id)
        return PickupCredential(secret, expiresAt)
    }

    fun revokeCredential(tx: Tx, ctx: Context, id: String): PackageView {
        val record = packageRecord(tx, ctx, id)
        val data = record.decode<PackageData>()
        requireRecipient(ctx, data)
        val updated = tx.update(record, body(data.copy(credentialHash = null, credentialExpiresAt = null,
            history = data.history + event(ctx, "credential_revoked"))))
        tx.audit(ctx, "package.credential_revoked", id)
        return view(updated)
    }

    fun delegate(tx: Tx, ctx: Context, id: String, userId: String, revoke: Boolean = false): PackageView {
        val record = packageRecord(tx, ctx, id)
        val data = record.decode<PackageData>()
        requireRecipient(ctx, data)
        requireOutstanding(data)
        if (userId == ctx.userId) badRequest("Recipient does not need delegation")
        if (!revoke) tx.requireMember(ctx.tenantId, ctx.location(), userId)
        val delegates = if (revoke) data.delegates - userId else data.delegates + userId
        if (delegates.size > 10) badRequest("A package can have at most 10 delegates")
        val action = if (revoke) "delegate_revoked" else "delegate_authorized"
        val updated = tx.update(record, body(data.copy(delegates = delegates,
            credentialHash = null, credentialExpiresAt = null, history = data.history + event(ctx, action))))
        tx.audit(ctx, "package.$action", id)
        return view(updated)
    }

    fun reportPickup(tx: Tx, ctx: Context, id: String): PackageView {
        val record = packageRecord(tx, ctx, id)
        val data = record.decode<PackageData>()
        ctx.allow("packages.read.own")
        if (data.receipt.recipientId != ctx.userId && ctx.userId !in data.delegates) forbidden()
        requireOutstanding(data)
        if (data.status == "PICKUP_REPORTED") return view(record)
        val updated = tx.update(record, body(data.copy(status = "PICKUP_REPORTED",
            history = data.history + event(ctx, "pickup_reported"))))
        tx.audit(ctx, "package.pickup_reported", id)
        return view(updated)
    }

    fun remind(tx: Tx, ctx: Context, id: String): PackageView {
        ctx.allow("packages.receive")
        val record = packageRecord(tx, ctx, id)
        val data = record.decode<PackageData>()
        requireOutstanding(data)
        if (data.lastReminderAt?.let { Instant.parse(it).plusSeconds(86400).isAfter(clock.instant()) } == true) {
            return view(record)
        }
        val updated = tx.update(record, body(data.copy(lastReminderAt = clock.instant().toString(),
            history = data.history + event(ctx, "reminder_sent"))))
        tx.notify(ctx.tenantId, ctx.location(), data.receipt.recipientId, "Package awaiting collection", "A delivery is still waiting for collection.")
        tx.audit(ctx, "package.reminder_sent", id)
        return view(updated)
    }

    fun confirmPickup(tx: Tx, ctx: Context, id: String, request: ConfirmPickup): PackageView {
        ctx.allow("packages.collect")
        val record = packageRecord(tx, ctx, id)
        val data = record.decode<PackageData>()
        requireOutstanding(data)
        tx.requireMember(ctx.tenantId, ctx.location(), request.collectorId)
        if (request.collectorId != data.receipt.recipientId && request.collectorId !in data.delegates) forbidden()
        val expires = data.credentialExpiresAt?.let(Instant::parse)
        val expected = data.credentialHash
        if (expected == null || expires == null || !expires.isAfter(clock.instant()) ||
            !MessageDigest.isEqual(expected.toByteArray(), digest(request.credential).toByteArray())) {
            forbidden()
        }
        release(tx, ctx, record.id, data)
        val updated = tx.update(record, body(data.copy(status = "COLLECTED", collectorId = request.collectorId,
            credentialHash = null, credentialExpiresAt = null, history = data.history + event(ctx, "pickup_confirmed"))))
        tx.audit(ctx, "package.pickup_confirmed", id)
        tx.notify(ctx.tenantId, ctx.location(), data.receipt.recipientId, "Package collected", "Collection of your delivery was confirmed.")
        return view(updated)
    }

    fun cancel(tx: Tx, ctx: Context, id: String): PackageView {
        ctx.allow("packages.manage")
        val record = packageRecord(tx, ctx, id)
        val data = record.decode<PackageData>()
        requireOutstanding(data)
        release(tx, ctx, id, data)
        val updated = tx.update(record, body(data.copy(status = "CANCELLED", credentialHash = null,
            credentialExpiresAt = null, history = data.history + event(ctx, "cancelled"))))
        tx.audit(ctx, "package.cancelled", id)
        return view(updated)
    }

    fun trustedPickup(tx: Tx, ctx: Context, request: LockerPickupEvent): LockerEventResult {
        ctx.allow("packages.collect")
        if (!ctx.userId.startsWith("integration:")) forbidden()
        if (request.eventId.isBlank() || request.eventId.length > 128 || request.type != "pickup_confirmed") {
            badRequest("Invalid locker event")
        }
        val integrationId = ctx.userId.removePrefix("integration:")
        val keyId = digest("${ctx.tenantId}:$integrationId:${request.eventId}")
        val fingerprint = digest(body(request).toString())
        tx.get("locker_event", keyId, ctx.tenantId)?.let {
            val previous = it.decode<StoredLockerEvent>()
            if (previous.fingerprint != fingerprint) conflict("Event ID has already been used with a different payload")
            return previous.result
        }
        val record = packageRecord(tx, ctx, request.packageId)
        val data = record.decode<PackageData>()
        val lockerId = data.receipt.lockerId ?: badRequest("Package is not stored in a locker")
        val locker = tx.requireRecord("locker", lockerId, ctx.tenantId, ctx.location()).decode<LockerData>()
        if (locker.integrationId != integrationId || data.receipt.compartmentId != request.compartmentId) forbidden()
        val occurredAt = parseInstant(request.occurredAt)
        if (occurredAt.isAfter(clock.instant().plusSeconds(300))) badRequest("Event timestamp is in the future")
        val stale = occurredAt.isBefore(Instant.parse(record.createdAt)) || data.status in setOf("COLLECTED", "CANCELLED")
        val result = LockerEventResult(request.eventId, if (stale) "ignored" else "applied")
        if (!stale) {
            tx.requireMember(ctx.tenantId, ctx.location(), request.collectorId)
            if (request.collectorId != data.receipt.recipientId && request.collectorId !in data.delegates) forbidden()
            release(tx, ctx, record.id, data)
            tx.update(record, body(data.copy(status = "COLLECTED", collectorId = request.collectorId,
                credentialHash = null, credentialExpiresAt = null, history = data.history + event(ctx, "trusted_pickup_confirmed"))))
            tx.notify(ctx.tenantId, ctx.location(), data.receipt.recipientId, "Package collected", "Collection was confirmed by the locker provider.")
        }
        tx.create("locker_event", ctx.tenantId, ctx.location(), data = body(StoredLockerEvent(fingerprint, result)), id = keyId)
        tx.audit(ctx, "locker.event_${result.status}", record.id)
        return result
    }

    private fun release(tx: Tx, ctx: Context, id: String, data: PackageData) {
        data.receipt.lockerId?.let { lockerId ->
            val record = tx.requireRecord("locker", lockerId, ctx.tenantId, ctx.location())
            val locker = record.decode<LockerData>()
            tx.update(record, body(locker.copy(compartments = locker.compartments.map {
                if (it.packageId == id) it.copy(packageId = null) else it
            })))
        }
    }

    fun lockers(tx: Tx, ctx: Context): List<LockerView> {
        if (!ctx.can("lockers.read") && !ctx.can("lockers.manage")) forbidden()
        return tx.list("locker", ctx.tenantId, ctx.location()).map { lockerView(it) }
    }

    fun saveLocker(tx: Tx, ctx: Context, request: LockerData, id: String? = null): LockerView {
