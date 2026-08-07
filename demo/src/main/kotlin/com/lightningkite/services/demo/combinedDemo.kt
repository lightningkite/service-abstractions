package com.lightningkite.services.demo

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.cache.*
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.toPhoneNumber
import com.lightningkite.services.database.*
import com.lightningkite.services.email.*
import com.lightningkite.services.files.*
import com.lightningkite.services.notifications.*
import com.lightningkite.services.phonecall.*
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.get
import com.lightningkite.services.sms.*
import com.lightningkite.services.speech.*
import com.lightningkite.services.subscription.*
import com.lightningkite.services.voiceagent.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory

/**
 * The real-world wiring pattern: one settings data class, decoded from one JSON config,
 * resolved through one shared [TestSettingContext] - every service on its fake/local scheme
 * so this runs with zero credentials. This is how an app assembles its services at startup;
 * the other demo files each zoom into a single subsystem.
 *
 * `ai` and `embedding` are omitted - they have no fake provider (see aiDemo.kt/embeddingDemo.kt).
 */
fun main() = runBlocking {
    val filesRoot = createTempDirectory("service-abstractions-combined-demo")
    try {
        val settingsJson = """
            {
                "database": "ram",
                "cache": "ram",
                "email": "test",
                "sms": "test",
                "files": "file://$filesRoot?serveUrl=files",
                "notifications": "test",
                "pubsub": "local",
                "phonecall": "test",
                "textToSpeech": "test",
                "speechToText": "test",
                "subscriptionPayments": "test",
                "voiceAgent": "test"
            }
        """.trimIndent()
        val context = TestSettingContext()
        val settings = Json.decodeFromString<AppSettings>(settingsJson)

        val database = settings.database("database", context)
        val cache = settings.cache("cache", context)
        val email = settings.email("email", context) as TestEmailService
        val sms = settings.sms("sms", context) as TestSMS
        val files = settings.files("files", context)
        val notifications = settings.notifications("notifications", context) as TestNotificationService
        val pubsub = settings.pubsub("pubsub", context)
        val phonecall = settings.phonecall("phonecall", context) as TestPhoneCallService
        val textToSpeech = settings.textToSpeech("tts", context)
        val speechToText = settings.speechToText("stt", context)
        val subscriptionPayments = settings.subscriptionPayments("payments", context) as TestSubscriptionService
        val voiceAgent = settings.voiceAgent("voiceagent", context)

        println("--- database ---")
        val notes = database.prepare(DatabaseTableDefinition<Task>())
        notes.insertOne(Task(title = "Wired up from one settings object"))
        println(notes.find(Condition.Always).toList())

        println("--- cache ---")
        cache.set("hits", 1)
        println("hits = ${cache.get<Int>("hits")}")

        println("--- email ---")
        email.send(
            Email(
                subject = "Welcome",
                from = EmailAddressWithName("app@example.com", "Demo App"),
                to = listOf(EmailAddressWithName("you@example.com")),
                plainText = "The whole app just started up.",
            )
        )
        println("captured emails: ${email.sentEmails.size}")

        println("--- sms ---")
        sms.send("+15550000000".toPhoneNumber(), "The whole app just started up.")
        println("captured sms: ${sms.messageHistory.size}")

        println("--- files ---")
        val file = files.root.then("startup-note.txt")
        file.put(TypedData(Data.Text("The whole app just started up."), MediaType.Text.Plain))
        println("read back: ${file.get()?.text()}")

        println("--- notifications ---")
        notifications.send(listOf("device-token"), NotificationData(notification = Notification(title = "Welcome")))
        println("captured: ${notifications.lastMessageSent}")

        println("--- pubsub ---")
        val channel = pubsub.get<String>("app-events")
        val subscriber = launch { channel.collect { println("received: $it") } }
        delay(50)
        channel.emit("app started")
        delay(50)
        subscriber.cancel()

        println("--- phonecall ---")
        val callId = phonecall.startCall("+15551234567".toPhoneNumber())
        phonecall.speak(callId, "The whole app just started up.")
        println("call $callId spoke ${phonecall.spokenMessages}")
        phonecall.hangup(callId)
        println("status after hangup: ${phonecall.getCallStatus(callId)}")

        println("--- speech ---")
        val audio = textToSpeech.synthesize("The whole app just started up.")
        println("transcribed: ${speechToText.transcribe(audio).text}")

        println("--- subscription-payments ---")
        val customerId = subscriptionPayments.createCustomer(email = "you@example.com")
        println(subscriptionPayments.getCustomer(customerId))

        println("--- voiceagent ---")
        val session = voiceAgent.createSession()
        session.awaitConnection()
        val collector = launch {
            session.events.collect { event ->
                println("event: $event")
                if (event is VoiceAgentEvent.ResponseDone) session.close()
            }
        }
        session.createResponse()
        collector.join()

        println()
        println("All 11 fake-backed subsystems wired from one AppSettings + one TestSettingContext.")
    } finally {
        filesRoot.toFile().deleteRecursively()
    }
}

@Serializable
data class AppSettings(
    val database: Database.Settings = Database.Settings("ram"),
    val cache: Cache.Settings = Cache.Settings("ram"),
    val email: EmailService.Settings = EmailService.Settings("test"),
    val sms: SMS.Settings = SMS.Settings("test"),
    val files: ExternalFileSystem.Settings,
    val notifications: NotificationService.Settings = NotificationService.Settings("test"),
    val pubsub: PubSub.Settings = PubSub.Settings("local"),
    val phonecall: PhoneCallService.Settings = PhoneCallService.Settings("test"),
    val textToSpeech: TextToSpeechService.Settings = TextToSpeechService.Settings("test"),
    val speechToText: SpeechToTextService.Settings = SpeechToTextService.Settings("test"),
    val subscriptionPayments: SubscriptionService.Settings = SubscriptionService.Settings("test"),
    val voiceAgent: VoiceAgentService.Settings = VoiceAgentService.Settings("test"),
)
