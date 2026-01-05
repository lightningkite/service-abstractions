import com.lightningkite.deployhelpers.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.dokka)
    alias(libs.plugins.serialization)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(path = ":voiceagent"))

    // Ktor WebSocket client
    implementation(libs.ktorClientCore)
    implementation(libs.ktorClientCio)
    implementation(libs.ktorClientWebsockets)
    implementation(libs.ktorContentNegotiation)
    implementation(libs.ktorJson)

    implementation(libs.coroutinesCore)

    // CRaC for AWS Lambda SnapStart support
    implementation(libs.crac)

    // Logging
    implementation(libs.kotlinLogging)

    // Testing
    testImplementation(libs.kotlinTest)
    testImplementation(libs.coroutinesTesting)
    testImplementation("org.slf4j:slf4j-simple:2.0.9")
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.set(listOf("-Xcontext-parameters"))
    }
    explicitApi()
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

lkLibrary("lightningkite", "service-abstractions") {}

// Task to run the voice agent demo
tasks.register<JavaExec>("runVoiceAgentDemo") {
    group = "application"
    description = "Run the interactive voice agent demo (requires OPENAI_API_KEY env var)"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.lightningkite.services.voiceagent.openai.VoiceAgentDemoKt")
}
