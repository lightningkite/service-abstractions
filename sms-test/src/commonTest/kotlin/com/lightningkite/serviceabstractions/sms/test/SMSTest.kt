package com.lightningkite.serviceabstractions.sms.test

import com.lightningkite.serviceabstractions.HealthStatus
import com.lightningkite.serviceabstractions.TestSettingContext
import com.lightningkite.serviceabstractions.sms.ConsoleSMS
import com.lightningkite.serviceabstractions.sms.SMS
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SMSTest {
    
    private lateinit var testContext: TestSettingContext
    private lateinit var testSMS: TestSMS
    private lateinit var consoleSMS: ConsoleSMS
    
    @BeforeTest
    fun setup() {
        testContext = TestSettingContext()
        testSMS = TestSMS(testContext)
        consoleSMS = ConsoleSMS(testContext)
        
        // Reset the test SMS instance
        TestSMSInstance.reset()
    }
    
    @Test
    fun testConsoleSMS() = runTest {
        // Test sending a message with ConsoleSMS
        consoleSMS.send("1234567890", "Test message")
        
        // Verify metrics were recorded
        val metrics = testContext.metricSink.metrics
        assertTrue(metrics.isNotEmpty())
        assertTrue(metrics.any { it.first.path.contains("send") })
    }
    
    @Test
    fun testTestSMS() = runTest {
        // Test sending a message with TestSMS
        val phoneNumber = "1234567890"
        val messageText = "Test message"
        testSMS.send(phoneNumber, messageText)
        
        // Verify the message was stored
        val lastMessage = testSMS.lastMessageSent
        assertNotNull(lastMessage)
        assertEquals(phoneNumber, lastMessage.to)
        assertEquals(messageText, lastMessage.message)
        
        // Verify message history
        assertEquals(1, testSMS.messageHistory.size)
        
        // Send another message
        val secondNumber = "0987654321"
        val secondText = "Another test"
        testSMS.send(secondNumber, secondText)
        
        // Verify history updated
        assertEquals(2, testSMS.messageHistory.size)
        assertEquals(secondNumber, testSMS.lastMessageSent?.to)
        
        // Verify metrics were recorded
        val metrics = testContext.metricSink.metrics
        assertTrue(metrics.isNotEmpty())
        assertTrue(metrics.any { it.first.path.contains("send") })
    }
    
    @Test
    fun testCallbackInTestSMS() = runTest {
        var callbackCalled = false
        var callbackMessage: TestSMS.Message? = null
        
        // Set up callback
        testSMS.onMessageSent = { message ->
            callbackCalled = true
            callbackMessage = message
        }
        
        // Send message
        val phoneNumber = "1234567890"
        val messageText = "Test message"
        testSMS.send(phoneNumber, messageText)
        
        // Verify callback was called
        assertTrue(callbackCalled)
        assertNotNull(callbackMessage)
        assertEquals(phoneNumber, callbackMessage?.to)
        assertEquals(messageText, callbackMessage?.message)
    }
    
    @Test
    fun testHealthCheck() = runTest {
        // Test health check for ConsoleSMS
        val healthStatus = consoleSMS.healthCheck()
        assertEquals(HealthStatus.Level.OK, healthStatus.level)
        
        // Test health check for TestSMS
        val testHealthStatus = testSMS.healthCheck()
        assertEquals(HealthStatus.Level.OK, testHealthStatus.level)
    }
    
    @Test
    fun testSettingsWithTestProtocol() = runTest {
        // Register the test protocol
        SMS.Settings.registerTestProtocol()
        
        // Create settings with test protocol
        val settings = SMS.Settings(url = "test")
        
        // Create SMS instance from settings
        val sms = settings.invoke(testContext)
        
        // Verify it's a TestSMS instance
        assertTrue(sms is TestSMS)
        
        // Test sending a message
        val phoneNumber = "1234567890"
        val messageText = "Test from settings"
        sms.send(phoneNumber, messageText)
        
        // Verify the message was stored in the TestSMS instance
        val testInstance = sms as TestSMS
        assertNotNull(testInstance.lastMessageSent)
        assertEquals(phoneNumber, testInstance.lastMessageSent?.to)
        assertEquals(messageText, testInstance.lastMessageSent?.message)
    }
}