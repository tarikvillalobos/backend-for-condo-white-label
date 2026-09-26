package com.community.api.identity

import com.community.api.core.*
import java.time.Instant
import kotlin.test.*

class IdentityChallengesTest {
    @Test
    fun `invitation activation is single use and enables the invited account`() = Database.memory().use { db ->
        db.seedIdentity()
        val invitation = db.tx { it.issueInvitation(tenantA, "new@example.com", "New resident") }
        assertFalse(db.tx { it.get("account", invitation.userId, tenantA)!!.decode<Account>().active })
        db.tx { it.activate(ActivationRequest(invitation.token, testPassword), "test-host", "activation") }.unwrap()
        assertTrue(db.tx { it.get("account", invitation.userId, tenantA)!!.decode<Account>().active })
        assertFailsWith<ApiException> {
            db.tx { it.activate(ActivationRequest(invitation.token, testPassword), "test-host", "activation") }.unwrap()
        }
        db.tx { it.login(LoginRequest(tenantA, "new@example.com", testPassword), "test-host") }.unwrap()
    }

    @Test
    fun `recovery hides existence and consumes private delivery with all sessions revoked`() = Database.memory().use { db ->
        val account = db.seedIdentity()
        val session = db.signIn()
        val exists = db.tx { it.requestRecovery(EmailRequest(tenantA, testEmail), "test-host", false) }.unwrap()
        val absent = db.tx { it.requestRecovery(EmailRequest(tenantA, "absent@example.com"), "test-host", false) }.unwrap()
        assertEquals(exists, absent)
        val delivery = db.tx { it.list("auth_delivery", tenantA).single().decode<AuthDelivery>() }
        assertEquals(testEmail, delivery.email)
        db.tx { it.activate(ActivationRequest(delivery.credential, "new-strong-password-182"), "test-host", "recovery") }.unwrap()
        assertFailsWith<ApiException> { db.tx { it.authenticate(session.accessToken) } }
        assertTrue(db.tx { it.list("auth_delivery", tenantA).isEmpty() })
        assertTrue(Passwords.verify("new-strong-password-182", db.tx { it.get("account", account.id, tenantA)!!.decode<Account>().passwordHash }))
        assertFailsWith<ApiException> {
            db.tx { it.activate(ActivationRequest(delivery.credential, testPassword), "test-host", "recovery") }.unwrap()
        }
    }

    @Test
    fun `otp enforces five guesses and consumes a successful code once`() = Database.memory().use { db ->
        db.seedIdentity(otp = true)
        db.tx { it.requestRecovery(EmailRequest(tenantA, testEmail), "test-host", true) }.unwrap()
        val code = db.tx { it.list("auth_delivery", tenantA).single().decode<AuthDelivery>().credential }
        repeat(5) {
            assertFailsWith<ApiException> {
                db.tx { it.consumeOtp(OtpRequest(tenantA, testEmail, "invalid"), "test-host") }.unwrap()
            }
        }
        assertFailsWith<ApiException> { db.tx { it.consumeOtp(OtpRequest(tenantA, testEmail, code), "test-host") }.unwrap() }
        db.tx { it.requestRecovery(EmailRequest(tenantA, testEmail), "test-host", true) }.unwrap()
        val next = db.tx { it.list("auth_delivery", tenantA).single().decode<AuthDelivery>().credential }
        val session = db.tx { it.consumeOtp(OtpRequest(tenantA, testEmail, next), "test-host") }.unwrap()
        db.tx { it.authenticate(session.accessToken) }
        assertFailsWith<ApiException> { db.tx { it.consumeOtp(OtpRequest(tenantA, testEmail, next), "test-host") }.unwrap() }
    }

    @Test
    fun `expired invitation cannot activate account`() = Database.memory().use { db ->
        db.seedIdentity()
        val invitation = db.tx { it.issueInvitation(tenantA, "new@example.com", "New resident") }
        db.tx { tx ->
            val challenge = tx.get("auth_challenge", invitation.token.split('.')[1], tenantA)!!
            tx.update(challenge, body(challenge.decode<ChallengeData>().copy(expiresAt = Instant.now().minusSeconds(1).toString())))
        }
        assertFailsWith<ApiException> {
            db.tx { it.activate(ActivationRequest(invitation.token, testPassword), "test-host", "activation") }.unwrap()
        }
    }
}
