package com.community.api.platform

import com.community.api.core.*
import com.community.api.identity.Account
import com.community.api.identity.SessionData
import com.community.api.identity.issueSession
import com.community.api.module
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.*

class PlatformTest {
    @Test
    fun `tenant access cannot be selected by knowing an identifier`() {
        val f = PlatformFixture()
