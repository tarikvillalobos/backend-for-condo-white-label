package com.community.api.v1.community

import com.community.api.core.*
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID
import kotlin.test.assertTrue

internal class CommunityFixture : AutoCloseable {
    val db = Database.memory()
    val tenant = UUID.randomUUID().toString()
    val brand = UUID.randomUUID().toString()
    val condo = UUID.randomUUID().toString()
    val user = UUID.randomUUID().toString()
    val otherUser = UUID.randomUUID().toString()
    val unit = UUID.randomUUID().toString()
    val otherUnit = UUID.randomUUID().toString()
    val member = UUID.randomUUID().toString()
    val otherMember = UUID.randomUUID().toString()
