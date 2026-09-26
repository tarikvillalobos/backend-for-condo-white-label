package com.community.api.community

import com.community.api.core.*
import com.community.api.identity.Account
import com.community.api.identity.issueSession
import com.community.api.plugins.configureHttp
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.routing.routing
import kotlinx.serialization.json.*
import java.util.UUID

internal data class TestIdentity(val id: String, val token: String)
internal class CommunityFixture : AutoCloseable {
    val db = Database.memory()
    val tenant = UUID.randomUUID().toString()
    val location = UUID.randomUUID().toString()
    val unit = UUID.randomUUID().toString()
    val otherUnit = UUID.randomUUID().toString()
    init {
        db.tx { tx ->
