package com.community.api.v1.deliveries

import com.community.api.core.ApiException
import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*

internal fun validateCredential(c: V1Context): V1Response {
    val locker = c.lockerRecord()
    val supplied = c.input.text("code") ?: c.input.text("qrPayload") ?: c.fail(422, "VALIDATION_ERROR", "A code or QR payload is required")
    if (c.input.text("code") != null && c.input.text("qrPayload") != null) c.fail(422, "VALIDATION_ERROR", "Supply only one credential representation")
    val row = c.store.list("parcel", locker.locationId).firstOrNull {
        it.data.text("lockerId") == locker.id && credentialMatches(c, it, supplied)
    }
    val valid = row != null && locker.data.flag("available")
    return V1Response(obj("valid" to valid, "kind" to if (valid) "pickup" else null,
        "reason" to if (valid) null else "unknown", "parcelId" to row?.id.takeIf { valid },
        "compartmentCode" to row?.data?.text("compartmentCode").takeIf { valid },
        "inviteId" to null, "visitorName" to null, "unitLabel" to null,
        "node" to if (valid) c.node(row!!.data.text("nodeId")) else JsonNull, "consumedNow" to false))
