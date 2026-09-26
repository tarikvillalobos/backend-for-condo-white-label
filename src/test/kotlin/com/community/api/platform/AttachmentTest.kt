package com.community.api.platform

import com.community.api.core.*
import com.community.api.identity.issueSession
import com.community.api.module
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.*

class AttachmentTest {
    private val pdf = Base64.getEncoder().encodeToString("%PDF-1.7\nexample\n%%EOF".toByteArray())

    @Test
    fun `uploads validate filename media signature and decoded size`() {
        assertFailsWith<ApiException> { validateAttachment(AttachmentInput("../secret.pdf", "application/pdf", pdf)) }
