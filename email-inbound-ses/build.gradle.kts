import com.lightningkite.deployhelpers.lkLibrary

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.serialization)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(path = ":email"))
    api(project(path = ":email-inbound"))
    api(project(path = ":email-javasmtp"))  // For shared SES terraform (awsSesDomain)
    implementation(libs.angusMail)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.logging)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.testing)
    testImplementation(project(":test"))
    testImplementation(libs.bouncyCastle)

    // Lightning Server for live webhook testing
    testImplementation(libs.lightningServer.core) {
        exclude(group = "com.lightningkite.services")
    }
    testImplementation(project(path = ":cache"))
    testImplementation(project(path = ":database"))
    testImplementation(project(path = ":http-client"))
    testImplementation(project(path = ":otel-jvm"))
    testImplementation(project(path = ":pubsub"))
    testImplementation(project(path = ":kfile"))
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
        freeCompilerArgs.set(listOf("-Xcontext-parameters"))
    }
    explicitApi()
}

tasks.withType<JavaCompile>().configureEach {
    this.targetCompatibility = "17"
}

tasks.register<JavaExec>("runLightningServerDemo") {
    description = "Run the Lightning Server SES email demo with webhook handling"
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.lightningkite.services.email.ses.SesEmailInboundLightningServerDemo")
    standardInput = System.`in`
}

lkLibrary(
    "lightningkite",
    "service-abstractions",
    mavenAutomaticRelease = project.findProperty("mavenAutomaticRelease") as? Boolean ?: false
) {
    description.set("An inbound email implementation using the AWS SES api.")
}
