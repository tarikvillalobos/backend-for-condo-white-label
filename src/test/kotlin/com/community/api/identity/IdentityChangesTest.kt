package com.community.api.identity

import com.community.api.core.*
import java.time.Instant
import kotlin.test.*

class IdentityChangesTest {
    @Test
    fun `password change verifies current password and revokes pending recovery`() = Database.memory().use { db ->
        db.seedIdentity()
        val token = db.signIn()
        val actor = db.tx { it.authenticate(token.accessToken) }
        db.tx { it.requestRecovery(EmailRequest(tenantA, testEmail), "test-host", false) }.unwrap()
        assertFailsWith<ApiException> {
            db.tx { it.changePassword(actor, PasswordRequest("incorrect", testPassword), "test-host") }.unwrap()
        }
        db.tx { it.authenticate(token.accessToken) }
        db.tx { it.changePassword(actor, PasswordRequest(testPassword, "updated-password-593"), "test-host") }.unwrap()
        assertFailsWith<ApiException> { db.tx { it.authenticate(token.accessToken) } }
        assertTrue(db.tx { it.list("auth_delivery", tenantA).isEmpty() })
        assertTrue(db.tx { it.list("auth_challenge", tenantA).all { record -> record.decode<ChallengeData>().consumed } })
    }

    @Test
    fun `contact change requires new address verification and is bound to its user`() = Database.memory().use { db ->
        db.seedIdentity()
        val token = db.signIn()
        val actor = db.tx { it.authenticate(token.accessToken) }
        db.tx { it.requestContactChange(actor, ContactRequest("verified@example.com", testPassword), "test-host") }.unwrap()
        assertEquals(testEmail, db.tx { it.get("account", actor.userId, tenantA)!!.decode<Account>().email })
        val delivery = db.tx { it.list("auth_delivery", tenantA).single().decode<AuthDelivery>() }
        assertEquals("verified@example.com", delivery.email)
        assertFailsWith<ApiException> { db.tx { it.confirmContactChange(actor.copy(userId = "other"), delivery.credential) }.unwrap() }
        db.tx { it.confirmContactChange(actor, delivery.credential) }.unwrap()
        assertEquals("verified@example.com", db.tx { it.get("account", actor.userId, tenantA)!!.decode<Account>().email })
        assertFailsWith<ApiException> { db.tx { it.authenticate(token.accessToken) } }
    }

    @Test
    fun `refresh cannot replace verification for privileged changes`() = Database.memory().use { db ->
