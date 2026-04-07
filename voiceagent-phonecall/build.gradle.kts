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
    api(project(path = ":voiceagent"))
    api(project(path = ":phonecall"))
    api(project(path = ":pubsub"))

    // Logging
    implementation(libs.kotlin.logging)

    // Coroutines
    implementation(libs.coroutines.core)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.testing)
    testImplementation(project(path = ":test"))
    testImplementation(project(path = ":voiceagent-openai"))
    testImplementation(project(path = ":phonecall-twilio"))

    // Lightning Server for live webhook/websocket testing
    testImplementation(libs.lightningServer.core) {
        exclude(group = "com.lightningkite.services")
    }
    testImplementation(project(path = ":cache"))
    testImplementation(project(path = ":database"))
    testImplementation(project(path = ":http-client"))
    testImplementation(project(path = ":otel-jvm"))
    testImplementation(libs.lightningServer.typed) {
        exclude(group = "com.lightningkite.services")
    }
    testImplementation(libs.lightningServer.engine.netty) {
        exclude(group = "com.lightningkite.services")
    }
    testImplementation(libs.logBackClassic)
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

tasks.register<JavaExec>("runLightningServerDemo") {
    description = "Run the Lightning Server Phone Call Voice Agent demo"
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.lightningkite.services.voiceagent.phonecall.PhoneCallVoiceAgentLightningServerDemo")
    standardInput = System.`in`
}

lkLibrary("lightningkite", "service-abstractions") {}
