package com.community.api

import com.community.api.config.AppConfig
import com.community.api.config.Environment
import com.community.api.core.Database
import com.community.api.core.integrationRoutes
import com.community.api.identity.identityRoutes
import com.community.api.identity.MailConfig
import com.community.api.identity.deliverAuthMailBatch
import com.community.api.identity.deliverNotificationMailBatch
import com.community.api.platform.bootstrap
import com.community.api.platform.changeClientState
import com.community.api.platform.platformRoutes
import com.community.api.community.communityRoutes
import com.community.api.deliveries.deliveryRoutes
import com.community.api.reservations.reservationRoutes
import com.community.api.health.healthRoutes
import com.community.api.plugins.configureHttp
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing

fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("bootstrap"))) {
        bootstrap()
        return
    }
    if (args.contentEquals(arrayOf("client-state"))) {
        changeClientState()
        return
    }
    require(args.isEmpty()) { "Supported commands: bootstrap, client-state" }
    val config = AppConfig.fromEnvironment()
    val server = serverConfig {
        developmentMode = config.environment == Environment.DEVELOPMENT
        module { module(mailConfig = MailConfig.fromEnvironment()) }
    }
    embeddedServer(Netty, server) {
        connector {
            host = config.host
            port = config.port
        }
    }.start(wait = true)
}

fun Application.module(database: Database = Database.fromEnvironment(), mailConfig: MailConfig? = null) {
    monitor.subscribe(ApplicationStopped) { database.close() }
    configureHttp()
    if (mailConfig != null) launch(Dispatchers.IO) {
        while (isActive) {
            try {
                val batch = deliverAuthMailBatch(database, mailConfig)
                if (batch.failed > 0) log.warn("Authentication mail delivery failed for {} messages", batch.failed)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                log.error("Authentication mail worker failed: {}", failure.javaClass.simpleName)
            }
            delay(10_000)
        }
    }
    routing {
        healthRoutes(database)
        identityRoutes(database)
        platformRoutes(database)
        integrationRoutes(database)
        deliveryRoutes(database)
        reservationRoutes(database)
        communityRoutes(database)
    }
}
