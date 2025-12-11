package com.lightningkite.services.phonecall.twilio

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.phonecall.*
import com.lightningkite.toPhoneNumber
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Live tests for TwilioPhoneCallService that use real credentials.
 *
 * These tests require a `local/twilio.env` file with the following format:
 * ```
 * phoneNumber="+14355346971"
 * sid="SK..."
 * secret="..."
 * accountSid="AC..."
 * ```
 *
 * Run the main function to execute live tests.
 */
object TwilioPhoneCallServiceLiveTest {

    data class TwilioCredentials(
        val accountSid: String,
        val keySid: String,
        val keySecret: String,
        val phoneNumber: String
    )

    private fun loadCredentials(): TwilioCredentials? {
        // Try multiple possible locations for the credentials file
        val possiblePaths = listOf(
            "local/twilio.env",
            "../local/twilio.env",
            "../../local/twilio.env"
        )
        val envFile = possiblePaths.map { File(it) }.firstOrNull { it.exists() }

        if (envFile == null) {
            println("⚠️  No credentials file found")
            println("   Searched in: ${possiblePaths.joinToString(", ")}")
            println("   Create local/twilio.env with your Twilio credentials to run live tests.")
            return null
        }

        println("📁 Loading credentials from: ${envFile.absolutePath}")

        val props = mutableMapOf<String, String>()
        envFile.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                val (key, value) = trimmed.split("=", limit = 2)
                // Remove quotes if present
                props[key.trim()] = value.trim().removeSurrounding("\"")
            }
        }

        val accountSid = props["accountSid"]
        val keySid = props["sid"]
        val keySecret = props["secret"]
        val phoneNumber = props["phoneNumber"]

        if (accountSid == null || keySid == null || keySecret == null || phoneNumber == null) {
            println("⚠️  Missing required credentials in local/twilio.env")
            println("   Required: accountSid, sid, secret, phoneNumber")
            println("   Found: ${props.keys}")
            return null
        }

        return TwilioCredentials(accountSid, keySid, keySecret, phoneNumber)
    }

    private fun createService(credentials: TwilioCredentials): TwilioPhoneCallService {
        val context = TestSettingContext()
        return TwilioPhoneCallService(
            name = "twilio-live-test",
            context = context,
            account = credentials.accountSid,
            authUser = credentials.keySid,
            authSecret = credentials.keySecret,
            defaultFrom = credentials.phoneNumber
        )
    }

    // ==================== Tests ====================

    /**
     * Test 1: Health Check
     * Verifies that the Twilio API credentials are valid and the API is accessible.
     */
    suspend fun testHealthCheck(service: TwilioPhoneCallService): Boolean {
        println("\n📋 Test: Health Check")
        println("   Checking Twilio API connectivity...")

        return try {
            val health = service.healthCheck()
            when (health.level) {
                HealthStatus.Level.OK -> {
                    println("   ✅ Health check passed: ${health.additionalMessage}")
                    true
                }
                HealthStatus.Level.WARNING -> {
                    println("   ⚠️  Health check warning: ${health.additionalMessage}")
                    true
                }
                HealthStatus.Level.URGENT -> {
                    println("   🚨 Health check urgent: ${health.additionalMessage}")
                    false
                }
                HealthStatus.Level.ERROR -> {
                    println("   ❌ Health check failed: ${health.additionalMessage}")
                    false
                }
            }
        } catch (e: Exception) {
            println("   ❌ Health check threw exception: ${e.message}")
            false
        }
    }

    /**
     * Test 2: URL Settings Parsing
     * Verifies that the URL-based settings work correctly.
     */
    fun testUrlSettingsParsing(credentials: TwilioCredentials): Boolean {
        println("\n📋 Test: URL Settings Parsing")

        return try {
            val context = TestSettingContext()

            // Test API Key format
            val apiKeyUrl = "twilio://${credentials.accountSid}/${credentials.keySid}:${credentials.keySecret}@${credentials.phoneNumber}"
            println("   Testing API Key URL format...")
            val apiKeySettings = PhoneCallService.Settings(apiKeyUrl)
            val apiKeyService = apiKeySettings("test-api-key", context)
            println("   ✅ API Key URL parsed successfully")

            // Test helper function
            println("   Testing helper function...")
            with(TwilioPhoneCallService) {
                val helperSettings = PhoneCallService.Settings.twilioApiKey(
                    account = credentials.accountSid,
                    keySid = credentials.keySid,
                    keySecret = credentials.keySecret,
                    from = credentials.phoneNumber
                )
                val helperService = helperSettings("test-helper", context)
                println("   ✅ Helper function works correctly")
            }

            true
        } catch (e: Exception) {
            println("   ❌ URL parsing failed: ${e.message}")
            false
        }
    }

    /**
     * Test 3: TwiML Rendering
     * Verifies that call instructions render to valid TwiML.
     */
    fun testTwimlRendering(service: TwilioPhoneCallService): Boolean {
        println("\n📋 Test: TwiML Rendering")

        return try {
            // Test simple say + hangup
            println("   Testing Say -> Hangup...")
            val simple = service.renderInstructions(
                CallInstructions.Say(
                    text = "Hello, this is a test.",
                    then = CallInstructions.Hangup
                )
            )
            check(simple.contains("<Response>")) { "Missing Response tag" }
            check(simple.contains("<Say")) { "Missing Say tag" }
            check(simple.contains("<Hangup/>")) { "Missing Hangup tag" }
            println("   ✅ Simple TwiML rendered correctly")

            // Test gather with prompt
            println("   Testing Gather with prompt...")
            val gather = service.renderInstructions(
                CallInstructions.Gather(
                    prompt = "Press 1 for yes, 2 for no",
                    numDigits = 1,
                    actionUrl = "https://example.com/gather"
                )
            )
            check(gather.contains("<Gather")) { "Missing Gather tag" }
            check(gather.contains("numDigits=\"1\"")) { "Missing numDigits" }
            check(gather.contains("action=")) { "Missing action URL" }
            println("   ✅ Gather TwiML rendered correctly")

            // Test dial/forward
            println("   Testing Forward (Dial)...")
            val forward = service.renderInstructions(
                CallInstructions.Forward(to = "+15551234567".toPhoneNumber())
            )
            println("   Forward TwiML: $forward")
            check(forward.contains("<Dial")) { "Missing Dial tag" }
            check(forward.contains("<Number>")) { "Missing Number tag" }
            check(forward.contains("+15551234567")) { "Missing phone number" }
            println("   ✅ Forward TwiML rendered correctly")

            // Test reject
            println("   Testing Reject...")
            val reject = service.renderInstructions(CallInstructions.Reject)
            check(reject.contains("<Reject")) { "Missing Reject tag" }
            println("   ✅ Reject TwiML rendered correctly")

            // Test complex flow
            println("   Testing complex flow...")
            val complex = service.renderInstructions(
                CallInstructions.Say(
                    text = "Welcome to our service!",
                    then = CallInstructions.Gather(
                        prompt = "Press 1 for sales, 2 for support",
                        numDigits = 1,
                        actionUrl = "https://example.com/menu",
                        then = CallInstructions.Say(
                            text = "We didn't receive your input. Goodbye!",
                            then = CallInstructions.Hangup
                        )
                    )
                )
            )
            check(complex.contains("Welcome to our service!")) { "Missing initial message" }
            check(complex.contains("<Gather")) { "Missing Gather" }
            check(complex.contains("Goodbye!")) { "Missing goodbye message" }
            println("   ✅ Complex flow TwiML rendered correctly")

            println("   \n   Sample TwiML output:")
            println("   " + complex.lines().joinToString("\n   "))

            true
        } catch (e: Exception) {
            println("   ❌ TwiML rendering failed: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Test 4: Webhook Configuration
     * Tests that webhook subservices can be configured.
     */
    suspend fun testWebhookConfiguration(service: TwilioPhoneCallService): Boolean {
        println("\n📋 Test: Webhook Configuration")

        return try {
            println("   Configuring incoming call webhook...")
            service.onIncomingCall.configureWebhook("https://example.com/webhooks/incoming-call")
            println("   ✅ Incoming call webhook configured")

            println("   Configuring call status webhook...")
            service.onCallStatus.configureWebhook("https://example.com/webhooks/call-status")
            println("   ✅ Call status webhook configured")

            println("   Configuring transcription webhook...")
            service.onTranscription.configureWebhook("https://example.com/webhooks/transcription")
            println("   ✅ Transcription webhook configured")

            true
        } catch (e: Exception) {
            println("   ❌ Webhook configuration failed: ${e.message}")
            false
        }
    }

    // ==================== Main ====================

    /**
     * Interactive call demo - makes a real phone call!
     */
    suspend fun interactiveCallDemo(service: TwilioPhoneCallService, toPhoneNumber: String) {
        println("\n" + "=".repeat(60))
        println("📞 Interactive Call Demo")
        println("=".repeat(60))
        println("\n⚠️  WARNING: This will make a REAL phone call and incur Twilio charges!")
        println("   Calling: $toPhoneNumber")
        println("\n   Press Enter to continue or Ctrl+C to cancel...")
        readlnOrNull()

        try {
            println("\n📲 Starting call to $toPhoneNumber...")
            println("   (Waiting for you to answer and press a key...)")
            // Use machine detection to wait until user actually presses the key
            // postAnswerDelay of 12 seconds accounts for Twilio trial account verification message
            val callId = service.startCall(
                toPhoneNumber.toPhoneNumber(),
                OutboundCallOptions(
                    machineDetection = MachineDetectionMode.ENABLED,
                    postAnswerDelay = 12.seconds
                )
            )
            println("   ✅ Call connected! Call ID: $callId")

            // Check call status
            val status = service.getCallStatus(callId)
            println("   📊 Call status: ${status?.status ?: "unknown"}")

            println("\n   🎤 Speaking message (this will wait for TTS to complete)...")
            service.speak(callId, "Hello! This is a test call from the Twilio Phone Call Service. " +
                    "If you can hear this message, the integration is working correctly. " +
                    "Thank you for testing! Goodbye.")
            println("   ✅ Message completed!")

            println("\n   📴 Hanging up...")
            service.hangup(callId)
            println("   ✅ Call ended!")

            // Final status check
            kotlinx.coroutines.delay(1000)
            val finalStatus = service.getCallStatus(callId)
            println("\n   📊 Final call status: ${finalStatus?.status ?: "unknown"}")

            println("\n🎉 Interactive call demo completed successfully!")

        } catch (e: Exception) {
            println("\n   ❌ Call failed: ${e.message}")
            e.printStackTrace()
        }
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("=".repeat(60))
        println("🔊 Twilio Phone Call Service - Live Tests")
        println("=".repeat(60))

        val credentials = loadCredentials()
        if (credentials == null) {
            println("\n❌ Cannot run tests without credentials")
            return@runBlocking
        }

        println("\n📞 Using phone number: ${credentials.phoneNumber}")
        println("🔑 Account SID: ${credentials.accountSid}")
        println("🔑 API Key SID: ${credentials.keySid}")

        val service = createService(credentials)

        // Check for command line arguments
        println("Enter the target phone number:")
        val callTo = readln()
        interactiveCallDemo(service, callTo)
    }
}
