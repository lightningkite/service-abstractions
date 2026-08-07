import com.lightningkite.deployhelpers.*

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.serialization)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    // KSP generates DataClassPath objects (@GenerateDataClassPaths) for the database demo -
    // this must be `ksp(...)`, not `api(...)`, or the generator never runs.
    ksp(project(":database-processor"))

    api(project(":database"))
    api(project(":email"))
    api(project(":sms"))
    api(project(":notifications"))
    api(project(":cache"))
    api(project(":pubsub"))
    api(project(":files"))
    api(project(":phonecall"))
    api(project(":speech"))
    api(project(":subscription-payments"))
    api(project(":voiceagent"))
    api(project(":human-services"))

    // No fake/local scheme exists for these - their demos require a real provider
    // (see aiDemo.kt / embeddingDemo.kt), so only the core interfaces are needed here.
    api(project(":ai"))
    api(project(":embedding"))

    testImplementation(libs.coroutines.testing)
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
    sourceSets.test {
        kotlin.srcDir("build/generated/ksp/test/kotlin")
    }
}

tasks.withType<JavaCompile>().configureEach {
    this.targetCompatibility = "17"
}


// One JavaExec task per demo `fun main()`, all runnable as `./gradlew :demo:run<Name>`.
data class DemoRun(val taskName: String, val mainClass: String, val demoDescription: String)

val demoRuns = listOf(
    DemoRun("runCacheDemo", "SampleKt", "Cache: get/set/add/remove and TTL expiry via ram://"),
    DemoRun("runDatabaseDemo", "DatabaseDemoKt", "Database: insert/query/update/delete via ram:// with a @GenerateDataClassPaths model"),
    DemoRun("runEmailDemo", "EmailDemoKt", "Email: send via test:// and read back what was captured"),
    DemoRun("runSmsDemo", "SmsDemoKt", "SMS: send via test:// and read back what was captured"),
    DemoRun("runFilesDemo", "FilesDemoKt", "Files: write/read/signUrl/delete via file:// in a temp directory"),
    DemoRun("runNotificationsDemo", "NotificationsDemoKt", "Notifications: send via test:// and read back what was captured"),
    DemoRun("runPubsubDemo", "PubsubDemoKt", "PubSub: publish/subscribe round trip via local://"),
    DemoRun("runPhonecallDemo", "PhonecallDemoKt", "Phone calls: start/speak/hangup via test://"),
    DemoRun("runSpeechDemo", "SpeechDemoKt", "Speech: text-to-speech then speech-to-text round trip via test://"),
    DemoRun("runSubscriptionPaymentsDemo", "SubscriptionPaymentsDemoKt", "Subscription payments: create customer and checkout session via test://"),
    DemoRun("runVoiceagentDemo", "VoiceagentDemoKt", "Voice agent: create a session and collect a response via test://"),
    DemoRun("runAiDemo", "AiDemoKt", "AI: calls a real LLM provider from AI_URL (no fake provider exists)"),
    DemoRun("runEmbeddingDemo", "EmbeddingDemoKt", "Embedding: calls a real embedding provider from EMBEDDING_URL (no fake provider exists)"),
    DemoRun("runCombinedDemo", "CombinedDemoKt", "Combined: one settings JSON, one context, every fake-scheme service wired up together"),
    DemoRun("runHumanDemo", "HumanServicesDemoKt", "Human Services Dashboard on http://localhost:8800"),
)

demoRuns.forEach { demo ->
    tasks.register<JavaExec>(demo.taskName) {
        group = "demo"
        description = demo.demoDescription
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("com.lightningkite.services.demo.${demo.mainClass}")
    }
}

lkLibrary("lightningkite", "service-abstractions") {}
