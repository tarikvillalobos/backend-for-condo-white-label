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
    }

    @Test
    fun `random refresh secret cannot revoke a known session`() = Database.memory().use { db ->
        db.seedIdentity()
        val token = db.signIn()
        val forged = token.refreshToken.substringBeforeLast('.') + "." + secretToken()
        assertFailsWith<ApiException> { db.tx { it.refresh(forged, "test-host") }.unwrap() }
        db.tx { it.authenticate(token.accessToken) }
    }

    @Test
    fun `expired session and deactivated client are rejected immediately`() = Database.memory().use { db ->
        db.seedIdentity()
        val token = db.signIn()
        db.tx { tx ->
            val actor = tx.authenticate(token.accessToken)
            val session = tx.get("session", actor.sessionId, tenantA)!!
            tx.update(session, body(session.decode<SessionData>().copy(accessExpiresAt = Instant.now().minusSeconds(1).toString())))
        }
        assertFailsWith<ApiException> { db.tx { it.authenticate(token.accessToken) } }
        val fresh = db.signIn()
        db.tx { tx ->
            tx.update(tx.get("client", tenantA, tenantA)!!, buildJsonObject { put("active", false) })
        }
        assertFailsWith<ApiException> { db.tx { it.authenticate(fresh.accessToken) } }
    }

    @Test
    fun `failed login attempts persist and return rate limit`() = Database.memory().use { db ->
        db.seedIdentity()
        repeat(10) {
            val result = db.tx { it.login(LoginRequest(tenantA, testEmail, "x".repeat(257)), "attempt-host") }
            assertEquals(401, assertFailsWith<ApiException> { result.unwrap() }.status)
        }
        val result = db.tx { it.login(LoginRequest(tenantA, testEmail, testPassword), "attempt-host") }
        assertEquals(429, assertFailsWith<ApiException> { result.unwrap() }.status)
    }
}
