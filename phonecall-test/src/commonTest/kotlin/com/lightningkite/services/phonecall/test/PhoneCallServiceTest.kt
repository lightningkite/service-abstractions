package com.lightningkite.services.phonecall.test

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.phonecall.*
import com.lightningkite.toPhoneNumber
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhoneCallServiceTest {

    private lateinit var testContext: TestSettingContext
    private lateinit var testService: TestPhoneCallService
    private lateinit var consoleService: ConsolePhoneCallService

    @BeforeTest
    fun setup() {
        testContext = TestSettingContext()
        testService = TestPhoneCallService("testPhoneCall", testContext)
        consoleService = ConsolePhoneCallService("consolePhoneCall", testContext)
    }

    @Test
    fun testStartCall() = runTest {
        val phoneNumber = "+15551234567".toPhoneNumber()
        val callId = testService.startCall(phoneNumber)

        assertNotNull(callId)
        assertTrue(callId.isNotEmpty())

        val callInfo = testService.calls[callId]
        assertNotNull(callInfo)
        assertEquals(phoneNumber, callInfo.to)
        assertEquals(CallStatus.IN_PROGRESS, callInfo.status)
        assertEquals(CallDirection.OUTBOUND, callInfo.direction)
    }

    @Test
    fun testStartCallWithOptions() = runTest {
        val phoneNumber = "+15551234567".toPhoneNumber()
        val fromNumber = "+15559876543".toPhoneNumber()
        val options = OutboundCallOptions(
            from = fromNumber,
            transcriptionEnabled = true,
            initialMessage = "Hello!"
        )

        val callId = testService.startCall(phoneNumber, options)

        val callInfo = testService.calls[callId]
        assertNotNull(callInfo)
        assertEquals(fromNumber, callInfo.from)
        assertEquals(options, callInfo.options)
    }

    @Test
    fun testSpeak() = runTest {
        val phoneNumber = "+15551234567".toPhoneNumber()
        val callId = testService.startCall(phoneNumber)

        testService.speak(callId, "Hello, how can I help you?")

        assertEquals(1, testService.spokenMessages.size)
        assertEquals("Hello, how can I help you?", testService.spokenMessages[0].text)
        assertEquals(callId, testService.spokenMessages[0].callId)
    }

    @Test
    fun testSpeakWithVoice() = runTest {
        val phoneNumber = "+15551234567".toPhoneNumber()
        val callId = testService.startCall(phoneNumber)
        val voice = TtsVoice(language = "es-MX", gender = TtsGender.FEMALE)

        testService.speak(callId, "Hola!", voice)

        assertEquals(1, testService.spokenMessages.size)
        assertEquals(voice, testService.spokenMessages[0].voice)
    }

    @Test
    fun testSendDtmf() = runTest {
        val phoneNumber = "+15551234567".toPhoneNumber()
        val callId = testService.startCall(phoneNumber)

        testService.sendDtmf(callId, "123#")

        assertEquals(1, testService.sentDtmf.size)
        assertEquals("123#", testService.sentDtmf[0].digits)
        assertEquals(callId, testService.sentDtmf[0].callId)
    }

    @Test
    fun testHoldAndResume() = runTest {
        val phoneNumber = "+15551234567".toPhoneNumber()
        val callId = testService.startCall(phoneNumber)

        assertEquals(CallStatus.IN_PROGRESS, testService.calls[callId]?.status)

        testService.hold(callId)
        assertEquals(CallStatus.ON_HOLD, testService.calls[callId]?.status)

        testService.resume(callId)
        assertEquals(CallStatus.IN_PROGRESS, testService.calls[callId]?.status)
    }

    @Test
    fun testHangup() = runTest {
        val phoneNumber = "+15551234567".toPhoneNumber()
        val callId = testService.startCall(phoneNumber)

        testService.hangup(callId)

        assertEquals(CallStatus.COMPLETED, testService.calls[callId]?.status)
    }

    @Test
    fun testGetCallStatus() = runTest {
        val phoneNumber = "+15551234567".toPhoneNumber()
        val callId = testService.startCall(phoneNumber)

        val callInfo = testService.getCallStatus(callId)

        assertNotNull(callInfo)
        assertEquals(callId, callInfo.callId)
        assertEquals(CallStatus.IN_PROGRESS, callInfo.status)
    }

    @Test
    fun testRenderInstructions() = runTest {
        val instructions = CallInstructions.Say(
            text = "Hello!",
            then = CallInstructions.Hangup
        )

        val rendered = testService.renderInstructions(instructions)

        assertTrue(rendered.contains("Hello!"))
        assertTrue(rendered.contains("Hangup"))
        assertEquals(1, testService.renderedInstructions.size)
    }

    @Test
    fun testRenderGatherInstructions() = runTest {
        val instructions = CallInstructions.Gather(
            prompt = "Press 1 for sales, 2 for support.",
            numDigits = 1,
            actionUrl = "https://example.com/gather"
        )

        val rendered = testService.renderInstructions(instructions)

        assertTrue(rendered.contains("Gather"))
        assertTrue(rendered.contains("https://example.com/gather"))
    }

    @Test
    fun testRenderForwardInstructions() = runTest {
        val forwardTo = "+15559999999".toPhoneNumber()
        val instructions = CallInstructions.Forward(to = forwardTo)

        val rendered = testService.renderInstructions(instructions)

        assertTrue(rendered.contains("Dial"))
        assertTrue(rendered.contains(forwardTo.toString()))
    }

    @Test
    fun testHealthCheck() = runTest {
        val healthStatus = testService.healthCheck()
        assertEquals(HealthStatus.Level.OK, healthStatus.level)

        val consoleHealthStatus = consoleService.healthCheck()
        assertEquals(HealthStatus.Level.OK, consoleHealthStatus.level)
    }

    @Test
    fun testReset() = runTest {
        val phoneNumber = "+15551234567".toPhoneNumber()
        testService.startCall(phoneNumber)
        testService.speak("call-1", "Hello")

        testService.reset()

        assertTrue(testService.calls.isEmpty())
        assertTrue(testService.spokenMessages.isEmpty())
        assertTrue(testService.sentDtmf.isEmpty())
    }

    @Test
    fun testCallbackOnCallStarted() = runTest {
        var callbackInvoked = false
        var receivedCallInfo: TestPhoneCallService.TestCallInfo? = null

        testService.onCallStarted = { callInfo ->
            callbackInvoked = true
            receivedCallInfo = callInfo
        }

        val phoneNumber = "+15551234567".toPhoneNumber()
        testService.startCall(phoneNumber)

        assertTrue(callbackInvoked)
        assertNotNull(receivedCallInfo)
        assertEquals(phoneNumber, receivedCallInfo?.to)
    }

    @Test
    fun testCallbackOnCallEnded() = runTest {
        var callbackInvoked = false
        var receivedCallInfo: TestPhoneCallService.TestCallInfo? = null

        testService.onCallEnded = { callInfo ->
            callbackInvoked = true
            receivedCallInfo = callInfo
        }

        val phoneNumber = "+15551234567".toPhoneNumber()
        val callId = testService.startCall(phoneNumber)
        testService.hangup(callId)

        assertTrue(callbackInvoked)
        assertNotNull(receivedCallInfo)
        assertEquals(CallStatus.COMPLETED, receivedCallInfo?.status)
    }

    @Test
    fun testConsoleServiceStartCall() = runTest {
        val phoneNumber = "+15551234567".toPhoneNumber()
        val callId = consoleService.startCall(phoneNumber)

        assertNotNull(callId)
        assertTrue(callId.startsWith("console-call-"))
    }

    @Test
    fun testConsoleServiceSpeak() = runTest {
        val callId = consoleService.startCall("+15551234567".toPhoneNumber())
        // Should not throw
        consoleService.speak(callId, "Hello!")
    }

    @Test
    fun testConsoleServiceRenderInstructions() = runTest {
        val instructions = CallInstructions.Accept(
            transcriptionEnabled = true,
            initialMessage = "Hello!"
        )

        val rendered = consoleService.renderInstructions(instructions)

        assertTrue(rendered.contains("Response"))
        assertTrue(rendered.contains("Accept"))
    }
}
