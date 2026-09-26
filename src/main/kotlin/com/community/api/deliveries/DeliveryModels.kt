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
