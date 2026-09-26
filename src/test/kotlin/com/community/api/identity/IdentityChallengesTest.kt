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

