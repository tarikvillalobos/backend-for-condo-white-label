package com.community.api.identity

import com.community.api.community.NotificationPreferences
import com.community.api.community.notificationContext
import com.community.api.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
internal data class NotificationEmailDelivery(
    val status: String = "pending", val attempts: Int = 0, val nextAttemptAt: String? = null,
    val leaseId: String? = null, val leaseUntil: String? = null, val lastFailure: String? = null,
    val acceptedAt: String? = null,
)

@Serializable
data class NotificationDeliveryStatus(val status: String, val attempts: Int, val acceptedAt: String?, val failure: String?)

