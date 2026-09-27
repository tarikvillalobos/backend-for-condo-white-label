package com.community.api.v1.deliveries

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

internal fun lockerHandlers(): Map<String, V1Handler> = mapOf(
    "adminListLockers" to V1Handler { c -> V1Response(obj("items" to c.store.list("locker", c.condo()).map { c.lockerView(it) })) },
    "adminCreateLocker" to V1Handler(::saveLocker), "adminUpdateLocker" to V1Handler(::saveLocker),
    "adminSetCompartments" to V1Handler(::setCompartments), "adminUpdateCompartment" to V1Handler(::updateCompartment),
    "listCompartments" to V1Handler { c -> V1Response(obj("lockerId" to c.path.getValue("lockerId"), "compartments" to c.lockerRecord().data.array("compartments").map { c.project("Compartment", it.jsonObject) })) },
    "adminListDevices" to V1Handler { c -> V1Response(obj("items" to c.store.list("device", c.condo()).map { c.deviceView(it) })) },
    "adminCreateDevice" to V1Handler(::saveDevice), "adminRotateDeviceKey" to V1Handler(::saveDevice),
    "adminRevokeDevice" to V1Handler(::revokeDevice), "ingestLockerEvents" to V1Handler(::lockerEvents),
    "validatePickupCredential" to V1Handler(::validateCredential),
    "adminOpenCompartment" to V1Handler { c ->
        val locker = c.lockerRecord()
