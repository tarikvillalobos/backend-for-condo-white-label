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
            setProperty("mail.smtp.auth", (config.username != null).toString())
            setProperty("mail.smtp.starttls.enable", config.startTls.toString())
            setProperty("mail.smtp.starttls.required", config.startTls.toString())
            setProperty("mail.smtp.ssl.checkserveridentity", "true")
            setProperty("mail.smtp.ssl.protocols", "TLSv1.3 TLSv1.2")
            setProperty("mail.smtp.connectiontimeout", "5000")
            setProperty("mail.smtp.timeout", "5000")
            setProperty("mail.smtp.writetimeout", "5000")
        }
        val session = Session.getInstance(properties).apply { debug = false }
        val mime = object : MimeMessage(session) {
            override fun updateMessageID() { setHeader("Message-ID", "<${message.id}@community-api>") }
        }.apply {
            setFrom(InternetAddress(config.from, true).also { it.validate() })
            setRecipient(Message.RecipientType.TO, InternetAddress(message.to, true).also { it.validate() })
            setSubject(message.subject, "UTF-8")
            setText(message.text, "UTF-8")
            sentDate = Date()
            saveChanges()
        }
