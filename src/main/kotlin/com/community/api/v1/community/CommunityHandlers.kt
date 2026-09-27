package com.community.api.v1.community

import com.community.api.v1.V1Handler

fun communityHandlers(): Map<String, V1Handler> = listOf(
    vehicleHandlers(), petHandlers(), visitorHandlers(), accessHandlers(), arrivalHandlers(),
    notificationHandlers(), announcementHandlers(), eventHandlers(), ticketHandlers(),
    maintenanceHandlers(), documentHandlers(), contactHandlers(), cameraHandlers(), dashboardHandlers(), reportHandlers(),
).flatMap { it.entries }.associate { it.key to it.value }
