package com.community.api

import com.community.api.config.AppConfig
import com.community.api.config.Environment
import com.community.api.health.healthRoutes
import com.community.api.plugins.configureHttp
import io.ktor.server.application.Application
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing

fun main() {
    val config = AppConfig.fromEnvironment()
    val server = serverConfig {
        developmentMode = config.environment == Environment.DEVELOPMENT
        module { module() }
    }
    embeddedServer(Netty, server) {
