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

            // Test conference
            println("   Testing Conference...")
            val conference = service.renderInstructions(
                CallInstructions.Conference(
                    name = "my-test-conference",
                    startOnEnter = true,
                    endOnExit = false,
                    muted = false,
                    beep = true,
                    waitUrl = "https://example.com/hold-music.mp3",
                    statusCallbackUrl = "https://example.com/conference-status",
                    statusCallbackEvents = listOf("join", "leave")
                )
            )
            println("   Conference TwiML: $conference")
            check(conference.contains("<Dial")) { "Missing Dial tag" }
            check(conference.contains("<Conference")) { "Missing Conference tag" }
            check(conference.contains("my-test-conference")) { "Missing conference name" }
            check(conference.contains("startConferenceOnEnter=\"true\"")) { "Missing startConferenceOnEnter" }
            check(conference.contains("endConferenceOnExit=\"false\"")) { "Missing endConferenceOnExit" }
            check(conference.contains("waitUrl=")) { "Missing waitUrl" }
            check(conference.contains("statusCallback=")) { "Missing statusCallback" }
            check(conference.contains("statusCallbackEvent=\"join leave\"")) { "Missing statusCallbackEvent" }
            println("   ✅ Conference TwiML rendered correctly")

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
     * Interactive conference demo - demonstrates conference call functionality!
     */
    suspend fun interactiveConferenceDemo(service: TwilioPhoneCallService, phoneNumber1: String, phoneNumber2: String? = null) {
        println("\n" + "=".repeat(60))
        println("🎙️  Interactive Conference Demo")
        println("=".repeat(60))
        println("\n⚠️  WARNING: This will make REAL phone calls and incur Twilio charges!")
        println("   Conference room: test-conference-${System.currentTimeMillis()}")
        println("   Participant 1: $phoneNumber1")
        if (phoneNumber2 != null) {
            println("   Participant 2: $phoneNumber2")
        }
        println("\n   This demo will:")
        println("   1. Add participant 1 to the conference")
        if (phoneNumber2 != null) {
            println("   2. Add participant 2 to the conference")
            println("   3. Both participants can talk to each other")
        } else {
            println("   2. You'll join the conference alone (useful for testing)")
        }
        println("   4. Calls will be ended after you hang up")
        println("\n   Press Enter to continue or Ctrl+C to cancel...")
        readlnOrNull()

        try {
            val conferenceName = "test-conference-${System.currentTimeMillis()}"

            println("\n📞 Adding participant 1 to conference: $phoneNumber1...")
            val call1Id = service.startCall(
                phoneNumber1.toPhoneNumber(),
                OutboundCallOptions(
                    machineDetection = MachineDetectionMode.ENABLED,
                    postAnswerDelay = 12.seconds
                )
            )
            println("   ✅ Call 1 connected! Call ID: $call1Id")

            println("\n   🎤 Greeting participant 1...")
            service.speak(call1Id, "Hello! Welcome to the conference call test. " +
                    "Please wait while we connect other participants.")

            println("\n   🎙️  Adding participant 1 to conference room: $conferenceName...")
            service.updateCall(call1Id, CallInstructions.Conference(
                name = conferenceName,
                startOnEnter = true,
                endOnExit = phoneNumber2 == null, // End conference if only one participant
                muted = false,
                beep = true
            ))
            println("   ✅ Participant 1 is now in the conference!")

            if (phoneNumber2 != null) {
                println("\n📞 Adding participant 2 to conference: $phoneNumber2...")
                val call2Id = service.startCall(
                    phoneNumber2.toPhoneNumber(),
                    OutboundCallOptions(
                        machineDetection = MachineDetectionMode.ENABLED,
                        postAnswerDelay = 12.seconds
                    )
                )
                println("   ✅ Call 2 connected! Call ID: $call2Id")

                println("\n   🎤 Greeting participant 2...")
                service.speak(call2Id, "Hello! Welcome to the conference call test. " +
                        "Connecting you now.")

                println("\n   🎙️  Adding participant 2 to conference room: $conferenceName...")
                service.updateCall(call2Id, CallInstructions.Conference(
                    name = conferenceName,
                    startOnEnter = true,
                    endOnExit = true, // End conference when last person leaves
                    muted = false,
                    beep = true
                ))
                println("   ✅ Participant 2 is now in the conference!")

                println("\n🎉 Both participants are now in the conference!")
                println("   Conference room: $conferenceName")
                println("   Participant 1 (Call ID: $call1Id)")
                println("   Participant 2 (Call ID: $call2Id)")
                println("\n   💬 The participants can now talk to each other.")
                println("   📴 Press Enter to end all calls...")
                readlnOrNull()

                println("\n   📴 Hanging up both calls...")
                service.hangup(call1Id)
                service.hangup(call2Id)
                println("   ✅ Conference ended!")
            } else {
                println("\n🎉 You are now in the conference!")
                println("   Conference room: $conferenceName")
                println("   Call ID: $call1Id")
                println("\n   💬 You're alone in the conference (useful for testing).")
                println("   📴 Press Enter to end the call...")
                readlnOrNull()

                println("\n   📴 Hanging up...")
                service.hangup(call1Id)
                println("   ✅ Conference ended!")
            }

            println("\n🎉 Interactive conference demo completed successfully!")

        } catch (e: Exception) {
            println("\n   ❌ Conference demo failed: ${e.message}")
            e.printStackTrace()
        }
    }

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

        // Menu
        println("\n" + "=".repeat(60))
        println("Select a test to run:")
        println("  1. Health Check")
        println("  2. TwiML Rendering Tests")
        println("  3. Interactive Call Demo (single call)")
        println("  4. Interactive Conference Demo (multi-party call)")
        println("  5. Run All Tests")
        println("=".repeat(60))
        print("\nEnter your choice (1-5): ")

        when (readln().trim()) {
            "1" -> {
                testHealthCheck(service)
            }
            "2" -> {
                testTwimlRendering(service)
            }
            "3" -> {
                println("\nEnter the target phone number:")
                val callTo = readln()
                interactiveCallDemo(service, callTo)
            }
            "4" -> {
                println("\nConference Demo - Connect two phone numbers in a conference call")
                println("Enter participant 1 phone number:")
                val phone1 = readln()
                println("Enter participant 2 phone number (or press Enter to test with just one participant):")
                val phone2 = readln().trim()
                if (phone2.isEmpty()) {
                    interactiveConferenceDemo(service, phone1)
                } else {
                    interactiveConferenceDemo(service, phone1, phone2)
                }
            }
            "5" -> {
                testHealthCheck(service)
                testUrlSettingsParsing(credentials)
                testTwimlRendering(service)
                println("\n✅ All automated tests completed!")
                println("   To test interactive features, run options 3 or 4.")
            }
            else -> {
                println("❌ Invalid choice")
            }
        }
    }
}
