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
        db.seedIdentity()
        val first = db.signIn()
        val actor = db.tx { it.authenticate(first.accessToken) }
        db.tx { tx ->
            tx.requireRecentAuthentication(actor)
            val session = tx.get("session", actor.sessionId, tenantA)!!
            tx.update(session, body(session.decode<SessionData>().copy(verifiedAt = Instant.now().minusSeconds(601).toString())))
        }
        val renewed = db.tx { it.refresh(first.refreshToken, "test-host") }.unwrap()
        db.tx { it.authenticate(renewed.accessToken) }
        assertEquals("verification_required", assertFailsWith<ApiException> { db.tx { it.requireRecentAuthentication(actor) } }.code)
        db.tx { it.verifyIdentity(actor, testPassword, "test-host") }.unwrap()
        db.tx { it.requireRecentAuthentication(actor) }
    }

    @Test
    fun `admin credential revocation prevents a pending invitation activating again`() = Database.memory().use { db ->
        db.seedIdentity()
        val invite = db.tx { it.issueInvitation(tenantA, "disabled@example.com", "Disabled") }
        db.tx { it.revokeAccountCredentials(tenantA, invite.userId) }
        assertFailsWith<ApiException> {
            db.tx { it.activate(ActivationRequest(invite.token, testPassword), "test-host", "activation") }.unwrap()
        }
    }
}
