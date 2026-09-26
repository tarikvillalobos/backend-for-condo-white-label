package com.community.api.identity

import com.community.api.core.*
import java.time.Instant

internal fun Tx.allowAttempt(operation: String, tenantId: String, email: String, remoteHost: String): Boolean {
    // Fixed hash buckets bound storage even when an attacker invents accounts and addresses.
    val host = takeRate(operation, "host:${digest(remoteHost).take(3)}", 30)
    val account = takeRate(operation, "account:${digest("$tenantId:$email").take(3)}", 10)
    return host && account
}

private fun Tx.takeRate(operation: String, bucket: String, limit: Int): Boolean {
    val id = "$operation:$bucket"
    val now = Instant.now().epochSecond
    val existing = get("auth_rate", id, "__authentication__")
    val old = existing?.decode<RateWindow>()
    val window = if (old == null || now - old.startedAt >= 900) RateWindow(now, 1)
    else old.copy(attempts = minOf(old.attempts + 1, limit + 1))
    if (existing == null) create("auth_rate", "__authentication__", data = body(window), id = id)
