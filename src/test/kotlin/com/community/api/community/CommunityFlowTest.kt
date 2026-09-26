package com.community.api.community

import com.community.api.core.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.*

class CommunityFlowTest {
    @Test
    fun `visitor admission requires staff and single use survives checkout`() = testApplication {
        CommunityFixture().use { f ->
            f.install(this)
            val now = Instant.now()
            val response = client.post(f.path("visitors")) {
