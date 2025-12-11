
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
    api(project(path = ":basis"))
    api(project(path = ":sms"))
    api(project(path = ":sms-inbound"))
    api(project(path = ":data"))

    // Ktor dependencies for HTTP client
    implementation(project(":http-client"))

    testImplementation(libs.kotlinTest)
    testImplementation(libs.coroutinesTesting)
    testImplementation(project(path = ":test"))

    // Lightning Server for live webhook testing
    testImplementation(libs.lightningserver.core) {
        exclude(group = "com.lightningkite.services")
    }
    testImplementation(project(path = ":cache"))
    testImplementation(project(path = ":database"))
    testImplementation(project(path = ":http-client"))
    testImplementation(project(path = ":otel-jvm"))
    testImplementation(project(path = ":pubsub"))
    testImplementation(libs.lightningserver.typed) {
        exclude(group = "com.lightningkite.services")
    }
    testImplementation(libs.lightningserver.engine.netty) {
        exclude(group = "com.lightningkite.services")
    }
    testImplementation(libs.logBackClassic)
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi"); freeCompilerArgs.set(listOf("-Xcontext-parameters"))
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
    description = "Run the Lightning Server SMS demo with webhook handling"
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.lightningkite.services.sms.twilio.TwilioSmsLightningServerDemo")
    standardInput = System.`in`
}

lkLibrary("lightningkite", "service-abstractions") {}
