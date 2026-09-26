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

    @Test
    fun `sessions cannot authenticate through another tenant or with refresh token`() = Database.memory().use { db ->
        val account = db.seedIdentity()
        val token = db.signIn()
        assertEquals(account.id, db.tx { it.authenticate(token.accessToken).userId })
        assertFailsWith<ApiException> { db.tx { it.authenticate(token.refreshToken) } }
        assertFailsWith<ApiException> { db.tx { it.authenticate(token.accessToken.replace(tenantA, tenantB)) } }
        assertFailsWith<ApiException> { db.tx { it.login(LoginRequest(tenantB, testEmail, testPassword), "other") }.unwrap() }
    }

    @Test
    fun `refresh rotation revokes previous access and replay revokes entire session`() = Database.memory().use { db ->
        db.seedIdentity()
        val first = db.signIn()
        val second = db.tx { it.refresh(first.refreshToken, "test-host") }.unwrap()
        assertFailsWith<ApiException> { db.tx { it.authenticate(first.accessToken) } }
        db.tx { it.authenticate(second.accessToken) }
        assertFailsWith<ApiException> { db.tx { it.refresh(first.refreshToken, "test-host") }.unwrap() }
        assertFailsWith<ApiException> { db.tx { it.authenticate(second.accessToken) } }
        assertFailsWith<ApiException> { db.tx { it.refresh(second.refreshToken, "test-host") }.unwrap() }
