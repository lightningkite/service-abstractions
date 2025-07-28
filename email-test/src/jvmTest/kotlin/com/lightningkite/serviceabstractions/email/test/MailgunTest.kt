package com.lightningkite.serviceabstractions.email.test

import com.lightningkite.serviceabstractions.TestSettingContext
import com.lightningkite.serviceabstractions.email.Email
import com.lightningkite.serviceabstractions.email.EmailAddressWithName
import com.lightningkite.serviceabstractions.email.EmailService
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Test for the Mailgun email service implementation.
 * This test is conditional on the presence of credentials in a local file.
 */
class MailgunTest {
    
    @Test
    fun testMailgun() {
        val credentials = File("local/test-mailgun.txt")
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
                    subject = "Mailgun Test",
                    to = listOf(EmailAddressWithName("joseph@lightningkite.com", "Joseph Ivie")),
                    plainText = "This is a test message from the migrated MailgunTest."
                )
            )
        }
        
        println("Test email sent successfully")
    }
}