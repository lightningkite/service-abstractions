import com.lightningkite.deployhelpers.lkLibrary

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(path = ":email-inbound"))
    api(project(path = ":http-client"))
    implementation(libs.angusMail)
    implementation(libs.kotlin.logging)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.testing)
    testImplementation(project(":test"))

    // GreenMail for IMAP/SMTP mock server testing
    testImplementation(libs.greenmail)

    // Lightning Server for webhook testing demo
//    testImplementation(libs.lightningServer.core) {
//        exclude(group = "com.lightningkite.services")
//    }
    testImplementation(project(path = ":cache"))
    testImplementation(project(path = ":database"))
    testImplementation(project(path = ":otel-jvm"))
    testImplementation(project(path = ":pubsub"))
//    testImplementation(libs.lightningServer.typed) {
//        exclude(group = "com.lightningkite.services")
//    }
//    testImplementation(libs.lightningServer.engine.netty) {
//        exclude(group = "com.lightningkite.services")
//    }
    testImplementation(libs.logBackClassic)
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

tasks.register<JavaExec>("runLightningServerDemo") {
    description = "Run the Lightning Server IMAP demo with polling and webhook delivery"
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.lightningkite.services.email.imap.ImapLightningServerDemo")
    standardInput = System.`in`
}

lkLibrary(
    "lightningkite",
    "service-abstractions",
    mavenAutomaticRelease = project.findProperty("mavenAutomaticRelease") as? Boolean ?: false
) {
    description.set("An inbound email implementation using an imap connection.")
}
