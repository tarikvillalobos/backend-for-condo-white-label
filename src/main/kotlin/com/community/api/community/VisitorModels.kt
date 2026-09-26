package com.community.api.community

import com.community.api.core.*
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.Duration
import java.util.Base64

@Serializable
data class VisitorInput(val name: String, val purpose: String, val validFrom: String, val validUntil: String,
    val unitId: String? = null, val singleUse: Boolean = true)
@Serializable
data class VisitorInvite(val content: VisitorInput, val credentialHash: String, val status: String = "expected",
    val checkedInAt: String? = null, val checkedOutAt: String? = null, val visits: Int = 0)
@Serializable
data class VisitorView(val id: String, val content: VisitorInput, val status: String, val checkedInAt: String?, val checkedOutAt: String?, val visits: Int)
@Serializable
data class VisitorCreated(val invitation: VisitorView, val admissionCode: String)
