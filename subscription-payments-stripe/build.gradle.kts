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
    api(project(path = ":basis"))
    api(project(path = ":subscription-payments"))

    // Stripe SDK
    implementation(libs.stripe)

    // Ktor dependencies for HTTP client (for webhook signature verification)
    implementation(project(":http-client"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.testing)
    testImplementation(project(path = ":test"))

    // Lightning Server for live webhook testing
    testImplementation(libs.lightningServer.core) {
        exclude(group = "com.lightningkite.services")
    }
    testImplementation(project(path = ":cache"))
    testImplementation(project(path = ":database"))
    testImplementation(project(path = ":database-jsonfile"))
    testImplementation(project(path = ":otel-jvm"))
    testImplementation(project(path = ":pubsub"))
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
    description = "Run the Lightning Server Stripe Subscription demo with webhook handling"
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.lightningkite.services.subscription.stripe.StripeSubscriptionLightningServerDemo")
    standardInput = System.`in`
}

lkLibrary("lightningkite", "service-abstractions") {}
