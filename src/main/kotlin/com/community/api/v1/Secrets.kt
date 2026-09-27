package com.community.api.v1

import com.community.api.core.ApiException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Secrets {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val key: ByteArray by lazy {
        val configured = System.getenv("API_ENCRYPTION_KEY")
        if (configured != null) decoder.decode(configured).also { require(it.size == 32) { "API_ENCRYPTION_KEY must encode 32 bytes" } }
        else {
            require(System.getenv("APP_ENV") != "production") { "Set API_ENCRYPTION_KEY in production" }
