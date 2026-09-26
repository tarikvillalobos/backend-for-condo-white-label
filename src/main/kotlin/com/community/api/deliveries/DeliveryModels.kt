package com.community.api.deliveries

import kotlinx.serialization.Serializable

@Serializable
data class ReceivePackage(
    val recipientId: String,
    val description: String,
    val carrier: String = "",
    val trackingNumber: String = "",
    val lockerId: String? = null,
    val compartmentId: String? = null,
    val collectionDeadline: String? = null,
)

@Serializable
data class DeliveryEvent(val action: String, val actorId: String, val at: String)

@Serializable
data class PackageData(
    val receipt: ReceivePackage,
    val status: String = "RECEIVED",
    val delegates: Set<String> = emptySet(),
    val credentialHash: String? = null,
    val credentialExpiresAt: String? = null,
    val collectorId: String? = null,
    val history: List<DeliveryEvent> = emptyList(),
    val lastReminderAt: String? = null,
)

@Serializable
data class PackageView(
    val id: String,
    val recipientId: String,
    val description: String,
    val carrier: String,
    val trackingNumber: String,
    val lockerId: String?,
    val compartmentId: String?,
    val collectionDeadline: String?,
    val status: String,
    val delegates: Set<String>,
    val collectorId: String?,
    val history: List<DeliveryEvent>,
)

@Serializable
data class Compartment(
    val id: String,
    val label: String,
    val maintenance: Boolean = false,
    val packageId: String? = null,
)

@Serializable
data class LockerData(
    val name: String,
    val compartments: List<Compartment>,
    val maintenance: Boolean = false,
    val integrationId: String? = null,
)

@Serializable
data class LockerView(val id: String, val name: String, val maintenance: Boolean, val compartments: List<Compartment>)

@Serializable
data class PickupCredential(val credential: String, val expiresAt: String)

@Serializable
data class CredentialRequest(val validForMinutes: Int = 30)

@Serializable
data class DelegationRequest(val userId: String)

@Serializable
data class ConfirmPickup(val collectorId: String, val credential: String)

@Serializable
data class ReceiptKey(val fingerprint: String, val packageId: String)

@Serializable
data class LockerPickupEvent(
    val eventId: String,
    val packageId: String,
    val compartmentId: String,
    val collectorId: String,
    val occurredAt: String,
    val type: String = "pickup_confirmed",
)

@Serializable
data class LockerEventResult(val eventId: String, val status: String)

@Serializable
data class StoredLockerEvent(val fingerprint: String, val result: LockerEventResult)
