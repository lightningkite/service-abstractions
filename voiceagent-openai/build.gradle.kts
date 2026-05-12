import com.lightningkite.deployhelpers.lkLibrary

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.serialization)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(path = ":voiceagent"))

    // Ktor WebSocket client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.contentNegotiation)
    implementation(libs.ktor.json)

    implementation(libs.coroutines.core)

    // CRaC for AWS Lambda SnapStart support
    implementation(libs.crac)

    // Logging
    implementation(libs.kotlin.logging)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.testing)
    testImplementation(libs.slf4j.simple)
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.set(listOf("-Xcontext-parameters"))
    }
    explicitApi()
}

tasks.withType<JavaCompile>().configureEach {
    this.targetCompatibility = "17"
}

// Task to run the voice agent demo
tasks.register<JavaExec>("runVoiceAgentDemo") {
    group = "application"
    description = "Run the interactive voice agent demo (requires OPENAI_API_KEY env var)"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.lightningkite.services.voiceagent.openai.VoiceAgentDemoKt")
}

lkLibrary(
    "lightningkite",
    "service-abstractions",
    mavenAutomaticRelease = project.findProperty("mavenAutomaticRelease") as? Boolean ?: false
) {
    description.set("An speech/dialog generator implementation using OpenAI.")
}