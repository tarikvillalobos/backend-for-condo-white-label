package com.community.api.v1

import com.community.api.core.ApiException
import kotlinx.serialization.json.*
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ContractOperation(val method: String, val path: String, val definition: JsonObject) {
    val id: String = definition.string("operationId")!!
}

object Contract {
    val yaml: String by lazy {
        checkNotNull(javaClass.getResourceAsStream("/contract/openapi.yaml")) { "Missing bundled OpenAPI" }
            .bufferedReader().use { it.readText() }
    }
