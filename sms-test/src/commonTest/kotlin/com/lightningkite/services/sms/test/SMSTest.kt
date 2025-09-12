package com.lightningkite.services.sms.test

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.sms.ConsoleSMS
import com.lightningkite.services.sms.TestSMS
import com.lightningkite.toPhoneNumber
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
        testSMS = TestSMS("testSMS", testContext)
        consoleSMS = ConsoleSMS("consoleSMS", testContext) }
    
    @Test
    fun testConsoleSMS() = runTest {
        // Test sending a message with ConsoleSMS
        consoleSMS.send("1234567890".toPhoneNumber(), "Test message")
    }
    
    @Test
    fun testTestSMS() = runTest {
        // Test sending a message with TestSMS
        val phoneNumber = "1234567890".toPhoneNumber()
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
        val secondNumber = "0987654321".toPhoneNumber()
        val secondText = "Another test"
        testSMS.send(secondNumber, secondText)
        
        // Verify history updated
        assertEquals(2, testSMS.messageHistory.size)
        assertEquals(secondNumber, testSMS.lastMessageSent?.to)
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
        val phoneNumber = "1234567890".toPhoneNumber()
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
}