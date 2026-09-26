package com.community.api.identity

import com.community.api.core.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.*

class IdentitySessionsTest {
    @Test
    fun `passwords use unique salts and reject invalid or weak credentials`() {
        val hash = Passwords.hash(testPassword)
        assertNotEquals(hash, Passwords.hash(testPassword))
        assertTrue(hash.startsWith("pbkdf2-sha256:600000:"))
        assertTrue(Passwords.verify(testPassword, hash))
        assertFalse(Passwords.verify("incorrect", hash))
        assertFalse(Passwords.verify(testPassword, null))
        assertFailsWith<ApiException> { Passwords.hash("short") }
    }

