package com.community.api.identity

import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Properties

data class MailMessage(val to: String, val subject: String, val text: String, val id: String)

fun interface MailSender {
    suspend fun send(config: MailConfig, message: MailMessage)
}

object SmtpMailSender : MailSender {
    override suspend fun send(config: MailConfig, message: MailMessage): Unit = withContext(Dispatchers.IO) {
        val properties = Properties().apply {
