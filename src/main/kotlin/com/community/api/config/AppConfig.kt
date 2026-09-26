package com.community.api.config

enum class Environment { DEVELOPMENT, TEST, PRODUCTION }

data class AppConfig(val host: String, val port: Int, val environment: Environment) {
    companion object {
        fun fromEnvironment(variables: Map<String, String> = System.getenv()): AppConfig {
            val host = variables["HOST"] ?: "127.0.0.1"
            require(host.isNotBlank()) { "HOST must not be blank" }
            val port = (variables["PORT"] ?: "8080").toIntOrNull()
            require(port != null && port in 1..65535) { "PORT must be between 1 and 65535" }
            val environment = when (variables["APP_ENV"] ?: "development") {
                "development" -> Environment.DEVELOPMENT
                "test" -> Environment.TEST
                "production" -> Environment.PRODUCTION
                else -> throw IllegalArgumentException("APP_ENV must be development, test, or production")
            }
            return AppConfig(host, port, environment)
        }
    }
