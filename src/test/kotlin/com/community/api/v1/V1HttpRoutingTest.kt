package com.community.api.v1

import com.community.api.core.Database
import com.community.api.module
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class V1HttpRoutingTest {
    @Test fun brandLookupErrorUsesProblemDocument() = testApplication {
        val db = Database.memory()
        application { module(db, enableLegacyApi = false) }
        val response = client.get("/v1/configuration") {
            header("X-Brand-Id", "00000000-0000-0000-0000-000000000000")
