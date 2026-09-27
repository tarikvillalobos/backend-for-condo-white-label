package com.community.api.v1.deliveries

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.Duration

fun deliveryHandlers(): Map<String, V1Handler> = mapOf(
    "listParcels" to V1Handler(::listParcels), "adminListParcels" to V1Handler(::listParcels),
    "organizationListParcels" to V1Handler(::listParcels),
    "getParcel" to V1Handler { c -> V1Response(c.parcelView(c.parcel())) },
    "adminGetParcel" to V1Handler { c -> V1Response(c.parcelView(c.parcel(), true)) },
    "getParcelMetrics" to V1Handler(::metrics), "getPickupCredential" to V1Handler { c -> V1Response(c.pickupView(c.parcel())) },
    "markManualPickup" to V1Handler(::manualPickup), "undoManualPickup" to V1Handler(::manualPickup),
    "addParcelDelegate" to V1Handler(::delegate), "removeParcelDelegate" to V1Handler(::delegate),
    "registerParcel" to V1Handler(::registerParcel), "handoverParcel" to V1Handler(::handover),
    "adminUpdateParcel" to V1Handler(::updateParcel), "adminExtendParcelDeadline" to V1Handler(::updateParcel),
    "adminReturnParcel" to V1Handler(::closeParcel), "adminCancelParcel" to V1Handler(::closeParcel),
    "adminResendParcelNotice" to V1Handler(::resendNotice), "adminReissuePickupCredential" to V1Handler(::reissue),
    "listSupportIssues" to V1Handler(::supportIssues), "createSupportIssue" to V1Handler(::createSupportIssue),
