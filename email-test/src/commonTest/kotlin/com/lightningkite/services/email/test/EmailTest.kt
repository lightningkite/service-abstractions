package com.lightningkite.services.email.test

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.email.ConsoleEmailService
import com.lightningkite.services.email.Email
import com.lightningkite.services.email.EmailAddressWithName
import com.lightningkite.services.email.EmailPersonalization
import com.lightningkite.services.email.EmailService
import com.lightningkite.services.email.TestEmailService
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EmailTest {
    
    private lateinit var testContext: TestSettingContext
    private lateinit var testEmailService: TestEmailService
    private lateinit var consoleEmailService: ConsoleEmailService
    
    @BeforeTest
    fun setup() {
        testContext = TestSettingContext()
        testEmailService = TestEmailService("testEmailService", testContext)
        consoleEmailService = ConsoleEmailService("consoleEmailService", testContext)
        
        // Clear any previous test emails
        testEmailService.clear()
    }
    
    @Test
    fun testConsoleEmailService() = runTest {
        // Test sending a message with ConsoleEmailService
        val email = createTestEmail()
        consoleEmailService.send(email)
    }
    
    @Test
    fun testTestEmailService() = runTest {
        // Test sending an email with TestEmailService
        val email = createTestEmail()
        testEmailService.send(email)
        
        // Verify the email was stored
        val lastEmail = testEmailService.lastEmail()
        assertNotNull(lastEmail)
        assertEquals(email.subject, lastEmail.subject)
        assertEquals(email.to.first().value, lastEmail.to.first().value)
        assertEquals(email.plainText, lastEmail.plainText)
        
        // Verify email history
        assertEquals(1, testEmailService.allEmails().size)
        
        // Send another email
        val secondEmail = createTestEmail("Another test", "another@example.com")
        testEmailService.send(secondEmail)
        
        // Verify history updated
        assertEquals(2, testEmailService.allEmails().size)
        assertEquals(secondEmail.subject, testEmailService.lastEmail()?.subject)
        
    }
    
    @Test
    fun testBulkEmailSending() = runTest {
        // Create a template email
        val template = Email(
            subject = "Hello {{name}}",
            to = listOf(),
            plainText = "Hello {{name}}! This is a test message.",
            html = "<p>Hello {{name}}! This is a test message.</p>"
        )
        
        // Create personalizations
        val personalizations = listOf(
            EmailPersonalization(
                to = listOf(EmailAddressWithName("user1@example.com", "User 1")),
                substitutions = mapOf("name" to "User 1")
            ),
            EmailPersonalization(
                to = listOf(EmailAddressWithName("user2@example.com", "User 2")),
                substitutions = mapOf("name" to "User 2")
            ),
            EmailPersonalization(
                to = listOf(EmailAddressWithName("user3@example.com", "User 3")),
                substitutions = mapOf("name" to "User 3")
            )
        )
        
        // Send bulk emails
        testEmailService.sendBulk(template, personalizations)
        
        // Verify emails were sent
        val emails = testEmailService.allEmails()
        assertEquals(3, emails.size)
        
        // Verify personalization was applied
        val user1Email = testEmailService.lastEmailTo("user1@example.com")
        assertNotNull(user1Email)
        assertEquals("Hello User 1", user1Email.subject)
        assertEquals("Hello User 1! This is a test message.", user1Email.plainText)
        assertEquals("<p>Hello User 1! This is a test message.</p>", user1Email.html)
        
    }
    
    @Test
    fun testHealthCheck() = runTest {
        // Test health check for ConsoleEmailService
        val healthStatus = consoleEmailService.healthCheck()
        assertEquals(HealthStatus.Level.OK, healthStatus.level)
        
        // Test health check for TestEmailService
        val testHealthStatus = testEmailService.healthCheck()
        assertEquals(HealthStatus.Level.OK, testHealthStatus.level)
    }
    
    @Test
    fun testSettingsWithTestProtocol() = runTest {
        // Create settings with test protocol
        val settings = EmailService.Settings(url = "test")
        
        // Create email service instance from settings
        val emailService = settings.invoke("email", testContext)
        
        // Verify it's a TestEmailService instance
        assertTrue(emailService is TestEmailService)
        
        // Test sending an email
        val email = createTestEmail()
        emailService.send(email)
        
        // Verify the email was stored in the TestEmailService instance
        val testInstance = emailService as TestEmailService
        assertNotNull(testInstance.lastEmail())
        assertEquals(email.subject, testInstance.lastEmail()?.subject)
        assertEquals(email.to.first().value, testInstance.lastEmail()?.to?.first()?.value)
    }
    
    @Test
    fun testSettingsWithConsoleProtocol() = runTest {
        // Create settings with console protocol
        val settings = EmailService.Settings(url = "console")
        
        // Create email service instance from settings
        val emailService = settings.invoke("email", testContext)
        
        // Verify it's a ConsoleEmailService instance
        assertTrue(emailService is ConsoleEmailService)
        
        // Test sending an email (no assertions needed, just verify it doesn't throw)
        val email = createTestEmail()
        emailService.send(email)
    }
    
    // Helper function to create a test email
    private fun createTestEmail(
        subject: String = "Test Email",
        to: String = "test@example.com",
        plainText: String = "This is a test email."
    ): Email {
        return Email(
            subject = subject,
            to = listOf(EmailAddressWithName(to)),
            plainText = plainText
        )
    }
}