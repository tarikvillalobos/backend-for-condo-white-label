package com.community.api.identity

import com.community.api.core.badRequest
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Passwords {
    private const val ITERATIONS = 600_000
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val dummy by lazy { hash("unused-constant-timing-password-41") }

    fun validate(password: String) {
        if (password.length !in 12..256) badRequest("Password must contain 12 to 256 characters")
    }

