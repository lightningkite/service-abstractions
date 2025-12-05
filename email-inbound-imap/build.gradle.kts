
import com.lightningkite.deployhelpers.github
import com.lightningkite.deployhelpers.mit
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
    api(project(path = ":email-inbound"))
    api(project(path = ":http-client"))
    implementation(libs.angusMail)
    implementation(libs.kotlinLogging)
    testImplementation(libs.kotlinTest)
    testImplementation(libs.coroutinesTesting)
    testImplementation(project(":test"))

    // GreenMail for IMAP/SMTP mock server testing
    testImplementation(libs.greenmail)

    // Lightning Server for webhook testing demo
    testImplementation(libs.lightningserver.core) {
        exclude(group = "com.lightningkite.services")
    }
    testImplementation(project(path = ":cache"))
    testImplementation(project(path = ":database"))
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
    description = "Run the Lightning Server IMAP demo with polling and webhook delivery"
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.lightningkite.services.email.imap.ImapLightningServerDemo")
    standardInput = System.`in`
}

lkLibrary("lightningkite", "service-abstractions") {}
