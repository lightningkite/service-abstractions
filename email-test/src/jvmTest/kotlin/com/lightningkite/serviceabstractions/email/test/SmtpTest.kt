package com.lightningkite.serviceabstractions.email.test

import com.lightningkite.serviceabstractions.TestSettingContext
import com.lightningkite.serviceabstractions.email.Email
import com.lightningkite.serviceabstractions.email.EmailAddressWithName
import com.lightningkite.serviceabstractions.email.EmailPersonalization
import com.lightningkite.serviceabstractions.email.EmailService
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Test for the SMTP email service implementation.
 * This test is conditional on the presence of credentials in a local file.
 */
class SmtpTest {
    
    @Test
    fun testSmtp() {
        val credentials = File("local/test-smtp.json")
        if (!credentials.exists()) {
            println("No credentials to test with at ${credentials.absolutePath}")
            return
        }
        
        // Create a context for the email service
        val context = TestSettingContext()
        
        // Create the email service from the credentials
        val emailService = EmailService.Settings(credentials.readText()).invoke(context)
        
        // Print information about the service (for debugging)
        println("Using email service: ${emailService::class.simpleName}")
        
        // Send a test email
        runBlocking {
            emailService.send(
                Email(
                    subject = "SMTP Test",
                    fromLabel = "Joseph Ivie",
                    fromEmail = "joseph@lightningkite.com",
                    to = listOf(EmailAddressWithName("joseph@lightningkite.com", "Joseph Ivie")),
                    plainText = "This is a test message from the migrated SmtpTest.",
                    html = "<p>This is a test message from the migrated SmtpTest.</p>"
                )
            )
        }
        
        println("Test email sent successfully")
    }
    
    @Test
    fun testSendBulk() {
        val credentials = File("local/test-smtp.json")
        if (!credentials.exists()) {
            println("No credentials to test with at ${credentials.absolutePath}")
            return
        }
        
        // Create a context for the email service
        val context = TestSettingContext()
        
        // Create the email service from the credentials
        val emailService = EmailService.Settings(credentials.readText()).invoke(context)
        
        // Print information about the service (for debugging)
        println("Using email service: ${emailService::class.simpleName}")
        
        // Create test email addresses
        val email1 = EmailAddressWithName(
            "joseph@lightningkite.com",
            "Joseph Ivie One"
        )
        val email2 = EmailAddressWithName(
            "joseph+two@lightningkite.com",
            "Joseph Ivie Two"
        )
        val email3 = EmailAddressWithName(
            "joseph+three@lightningkite.com",
            "Joseph Ivie Three"
        )
        
        // Send bulk emails
        runBlocking {
            emailService.sendBulk(
                Email(
                    subject = "Bulk Email Test",
                    fromLabel = "Joseph Ivie",
                    fromEmail = "joseph@lightningkite.com",
                    to = emptyList(),
                    plainText = "Hello {{UserName}}!",
                    html = "<p>Hello {{UserName}}!</p>"
                ),
                personalizations = listOf(
                    EmailPersonalization(
                        to = listOf(email1),
                        cc = listOf(email2),
                        bcc = listOf(email3),
                        substitutions = mapOf("UserName" to email1.label!!)
                    ),
                    EmailPersonalization(
                        to = listOf(email2),
                        cc = listOf(email3),
                        bcc = listOf(email1),
                        substitutions = mapOf("UserName" to email2.label!!)
                    ),
                    EmailPersonalization(
                        to = listOf(email3),
                        cc = listOf(email1),
                        bcc = listOf(email2),
                        substitutions = mapOf("UserName" to email3.label!!)
                    ),
                    EmailPersonalization(
                        to = listOf(email1, email2, email3),
                        substitutions = mapOf(
                            "UserName" to listOf(
                                email1.label!!,
                                email2.label!!,
                                email3.label!!
                            ).joinToString(", ")
                        )
                    )
                )
            )
        }
        
        println("Bulk test emails sent successfully")
    }
}