package com.community.api.v1

import com.community.api.core.*
import com.community.api.v1.community.communityHandlers
import com.community.api.v1.deliveries.deliveryHandlers
import com.community.api.v1.identity.identityHandlers
import com.community.api.v1.platform.platformHandlers
import com.community.api.v1.reservations.reservationHandlers
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.util.UUID

fun v1Handlers(): Map<String,V1Handler> {
    val groups = listOf(identityHandlers(),communityHandlers(),deliveryHandlers(),reservationHandlers(),platformHandlers(),contextHandlers(),fileHandlers(),auditHandlers(),healthHandlers())
